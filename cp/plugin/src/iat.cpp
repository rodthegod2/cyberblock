#include "iat.hpp"
#include <cstdint>
#include <cstring>
#include <string.h> // _stricmp

namespace
{
	/// The import table entry (address slot) of `function` imported from `dll` by `module`, or null.
	void **find_entry(HMODULE module, const char *dll, const char *function)
	{
		const auto base = reinterpret_cast<const uint8_t *>(module);
		const auto *dos = reinterpret_cast<const IMAGE_DOS_HEADER *>(base);
		if (module == nullptr || dos->e_magic != IMAGE_DOS_SIGNATURE)
			return nullptr;
		const auto *nt = reinterpret_cast<const IMAGE_NT_HEADERS *>(base + dos->e_lfanew);
		if (nt->Signature != IMAGE_NT_SIGNATURE)
			return nullptr;
		const IMAGE_DATA_DIRECTORY &dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
		if (dir.VirtualAddress == 0)
			return nullptr;

		for (const auto *d = reinterpret_cast<const IMAGE_IMPORT_DESCRIPTOR *>(base + dir.VirtualAddress); d->Name != 0; ++d)
		{
			if (_stricmp(reinterpret_cast<const char *>(base + d->Name), dll) != 0)
				continue;
			// OriginalFirstThunk keeps the names even after the loader fills FirstThunk with addresses
			const DWORD names = d->OriginalFirstThunk != 0 ? d->OriginalFirstThunk : d->FirstThunk;
			const auto *name = reinterpret_cast<const IMAGE_THUNK_DATA *>(base + names);
			auto *address = reinterpret_cast<IMAGE_THUNK_DATA *>(const_cast<uint8_t *>(base) + d->FirstThunk);
			for (; name->u1.AddressOfData != 0; ++name, ++address)
			{
				if (IMAGE_SNAP_BY_ORDINAL(name->u1.Ordinal))
					continue;
				const auto *byName = reinterpret_cast<const IMAGE_IMPORT_BY_NAME *>(base + name->u1.AddressOfData);
				if (std::strcmp(byName->Name, function) == 0)
					return reinterpret_cast<void **>(&address->u1.Function);
			}
		}
		return nullptr;
	}

	/// Writes `value` into `*entry`, making the page writable for the moment.
	bool write_entry(void **entry, void *value)
	{
		DWORD old = 0;
		if (!VirtualProtect(entry, sizeof(void *), PAGE_READWRITE, &old))
			return false;
		*entry = value;
		DWORD ignored = 0;
		VirtualProtect(entry, sizeof(void *), old, &ignored);
		return true;
	}
}

namespace cb::iat
{
	bool patch(HMODULE module, const char *dll, const char *function, void *replacement, void **original)
	{
		void **entry = find_entry(module, dll, function);
		if (entry == nullptr)
			return false;
		void *const previous = *entry;
		void *const mine = *original;
		// `*original` is set before the entry changes: another thread may call `replacement` the moment it is in place
		*original = previous;
		if (write_entry(entry, replacement))
			return true;
		*original = mine;
		return false;
	}

	bool restore(HMODULE module, const char *dll, const char *function, void *replacement, void *original)
	{
		void **entry = find_entry(module, dll, function);
		if (entry == nullptr)
			return false;
		if (*entry != replacement)
			return *entry == original;
		return write_entry(entry, original);
	}
}
