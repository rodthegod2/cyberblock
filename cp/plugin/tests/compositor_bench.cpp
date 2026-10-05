// Megaplan 9-F1: what the compositor's upload of one Minecraft frame costs at each Performance mode scale, outside the game.
// compositor.cpp's upload path, step for step: the three layers (world colour RGBA8, depth R32F, overlay RGBA8) are staged
// row by row from a frame in memory (the shared memory stands in) into persistently mapped upload-heap buffers with 256-byte
// aligned rows, then copied into default-heap textures by one command list, fenced. Prints the CPU staging time and the
// GPU copy time (submit to fence) per frame, averaged, for 3840x2160, 2560x1440 and 1920x1080 at scale 1, 0.75 and 0.5.
// Built only on request (cmake --build ... --target compositor_bench); it needs a D3D12 GPU and touches no game.
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace
{
	constexpr int kFrames = 120;

	template <typename T>
	void release(T *&p)
	{
		if (p != nullptr)
			p->Release();
		p = nullptr;
	}

	struct Gpu
	{
		ID3D12Device *device = nullptr;
		ID3D12CommandQueue *queue = nullptr;
		ID3D12CommandAllocator *allocator = nullptr;
		ID3D12GraphicsCommandList *list = nullptr;
		ID3D12Fence *fence = nullptr;
		HANDLE event = nullptr;
		UINT64 value = 0;

		bool init()
		{
			if (FAILED(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device))))
				return false;
			D3D12_COMMAND_QUEUE_DESC q = {};
			q.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
			return SUCCEEDED(device->CreateCommandQueue(&q, IID_PPV_ARGS(&queue))) &&
				SUCCEEDED(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&allocator))) &&
				SUCCEEDED(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator, nullptr, IID_PPV_ARGS(&list))) &&
				SUCCEEDED(list->Close()) && SUCCEEDED(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence))) &&
				(event = CreateEventW(nullptr, FALSE, FALSE, nullptr)) != nullptr;
		}

		void wait()
		{
			queue->Signal(fence, ++value);
			if (fence->GetCompletedValue() < value)
			{
				fence->SetEventOnCompletion(value, event);
				WaitForSingleObject(event, INFINITE);
			}
		}

		~Gpu()
		{
			release(fence);
			release(list);
			release(allocator);
			release(queue);
			release(device);
			if (event != nullptr)
				CloseHandle(event);
		}
	};

	ID3D12Resource *buffer(ID3D12Device *device, UINT64 bytes)
	{
		D3D12_HEAP_PROPERTIES heap = {D3D12_HEAP_TYPE_UPLOAD};
		D3D12_RESOURCE_DESC d = {};
		d.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
		d.Width = bytes;
		d.Height = d.DepthOrArraySize = d.MipLevels = 1;
		d.SampleDesc.Count = 1;
		d.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
		ID3D12Resource *r = nullptr;
		device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &d, D3D12_RESOURCE_STATE_GENERIC_READ, nullptr, IID_PPV_ARGS(&r));
		return r;
	}

	ID3D12Resource *texture(ID3D12Device *device, UINT w, UINT h, DXGI_FORMAT format)
	{
		D3D12_HEAP_PROPERTIES heap = {D3D12_HEAP_TYPE_DEFAULT};
		D3D12_RESOURCE_DESC d = {};
		d.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
		d.Width = w;
		d.Height = h;
		d.DepthOrArraySize = d.MipLevels = 1;
		d.Format = format;
		d.SampleDesc.Count = 1;
		ID3D12Resource *r = nullptr;
		device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &d, D3D12_RESOURCE_STATE_COPY_DEST, nullptr, IID_PPV_ARGS(&r));
		return r;
	}

	/// compositor.cpp stage_layer: one memcpy when the pitches agree, else row by row.
	void stage(uint8_t *dst, const uint8_t *src, UINT w, UINT h, UINT pitch)
	{
		const size_t row = size_t(w) * 4;
		if (pitch == row)
		{
			std::memcpy(dst, src, row * h);
			return;
		}
		for (UINT y = 0; y < h; ++y)
			std::memcpy(dst + size_t(y) * pitch, src + size_t(y) * row, row);
	}

	bool bench(Gpu &gpu, UINT w, UINT h, double &stageMs, double &copyMs)
	{
		const UINT pitch = (w * 4 + 255) & ~255u;
		const size_t layer = size_t(w) * h * 4;
		std::vector<uint8_t> frame(layer * 3);
		for (size_t i = 0; i < frame.size(); ++i)
			frame[i] = uint8_t(i * 2654435761u >> 24);
		ID3D12Resource *upload[3] = {}, *tex[3] = {};
		uint8_t *mapped[3] = {};
		const DXGI_FORMAT formats[3] = {DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R32_FLOAT, DXGI_FORMAT_R8G8B8A8_UNORM};
		bool ok = true;
		for (int i = 0; i < 3 && ok; ++i)
		{
			upload[i] = buffer(gpu.device, UINT64(pitch) * h);
			tex[i] = texture(gpu.device, w, h, formats[i]);
			D3D12_RANGE none = {0, 0};
			ok = upload[i] != nullptr && tex[i] != nullptr && SUCCEEDED(upload[i]->Map(0, &none, reinterpret_cast<void **>(&mapped[i])));
		}
		stageMs = copyMs = 0.0;
		for (int f = 0; f < kFrames && ok; ++f)
		{
			const auto t0 = std::chrono::steady_clock::now();
			for (int i = 0; i < 3; ++i)
				stage(mapped[i], frame.data() + layer * i, w, h, pitch);
			const auto t1 = std::chrono::steady_clock::now();
			gpu.allocator->Reset();
			gpu.list->Reset(gpu.allocator, nullptr);
			for (int i = 0; i < 3; ++i)
			{
				D3D12_TEXTURE_COPY_LOCATION dst = {tex[i], D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX};
				D3D12_TEXTURE_COPY_LOCATION src = {upload[i], D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT};
				src.PlacedFootprint.Footprint = {formats[i], w, h, 1, pitch};
				gpu.list->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
			}
			gpu.list->Close();
			ID3D12CommandList *lists[] = {gpu.list};
			gpu.queue->ExecuteCommandLists(1, lists);
			gpu.wait();
			const auto t2 = std::chrono::steady_clock::now();
			if (f >= 10) // the first frames warm the caches and the driver
			{
				stageMs += std::chrono::duration<double, std::milli>(t1 - t0).count();
				copyMs += std::chrono::duration<double, std::milli>(t2 - t1).count();
			}
		}
		stageMs /= kFrames - 10;
		copyMs /= kFrames - 10;
		for (int i = 0; i < 3; ++i)
		{
			if (mapped[i] != nullptr)
				upload[i]->Unmap(0, nullptr);
			release(upload[i]);
			release(tex[i]);
		}
		return ok;
	}
}

int main()
{
	Gpu gpu;
	if (!gpu.init())
	{
		std::fprintf(stderr, "compositor_bench: no D3D12 device\n");
		return 1;
	}
	const UINT sizes[3][2] = {{3840, 2160}, {2560, 1440}, {1920, 1080}};
	const double scales[3] = {1.0, 0.75, 0.5};
	std::printf("backbuffer   scale  frame       staging (CPU) ms  copy (GPU) ms  total ms\n");
	for (const auto &s : sizes)
		for (double scale : scales)
		{
			// compositor::frame_size: each side times the scale, rounded half up, at least 1
			const UINT w = std::max(1u, UINT(std::floor(s[0] * scale + 0.5))), h = std::max(1u, UINT(std::floor(s[1] * scale + 0.5)));
			double stageMs = 0.0, copyMs = 0.0;
			if (!bench(gpu, w, h, stageMs, copyMs))
			{
				std::fprintf(stderr, "compositor_bench: could not create the %ux%u resources\n", w, h);
				return 1;
			}
			std::printf("%4ux%-4u    %.2f   %4ux%-4u   %16.2f  %13.2f  %8.2f\n", s[0], s[1], scale, w, h, stageMs, copyMs, stageMs + copyMs);
		}
	return 0;
}
