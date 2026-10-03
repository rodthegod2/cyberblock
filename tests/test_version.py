"""One version number everywhere (plan 2026-10-03-retest3-polish section 1.13): pyproject.toml and uv.lock, the CMake project
(and its CB_VERSION definitions), the DLL's kVersion, the Natives.reds header comment, mc/gradle.properties (which the mod jar
and fabric.mod.json take their version from) and the top entry of CHANGELOG.md all say the same thing."""
import re
from pathlib import Path

import pytest

from tools import package

REPO = Path(__file__).resolve().parents[1]
SEMVER = re.compile(r"\d+\.\d+\.\d+")


def text(rel: str) -> str:
    return (REPO / rel).read_text(encoding="utf-8")


def find(rel: str, pattern: str, flags: int = re.M) -> str:
    match = re.search(pattern, text(rel), flags)
    assert match, f"{rel}: no version found by /{pattern}/"
    return match.group(1)


def versions() -> dict[str, str]:
    return {
        "pyproject.toml": find("pyproject.toml", r'^version\s*=\s*"([^"]+)"'),
        "uv.lock": find("uv.lock", r'name = "cyberblock-tools"\r?\nversion = "([^"]+)"'),
        "cp/plugin/CMakeLists.txt": find("cp/plugin/CMakeLists.txt", r"project\(Cyberblock\s+VERSION\s+([0-9.]+)"),
        "mc/gradle.properties": find("mc/gradle.properties", r"^version=(\S+)"),
        "cp/scripts/Cyberblock/Natives.reds": find("cp/scripts/Cyberblock/Natives.reds", r'Cyberblock_Version\(\) -> String;\s*//\s*"([^"]+)"'),
        "CHANGELOG.md": find("CHANGELOG.md", r"^## \[(\d+\.\d+\.\d+)\]"),
    }


def test_every_place_says_the_same_semantic_version():
    found = versions()
    assert len(set(found.values())) == 1, f"versions disagree: {found}"
    assert SEMVER.fullmatch(found["pyproject.toml"]), found


def test_the_packager_reads_the_same_version():
    assert package.version(REPO) == versions()["pyproject.toml"]


def test_the_dll_takes_its_version_from_cmake_or_spells_the_same_one():
    expected = versions()["pyproject.toml"]
    natives = text("cp/plugin/src/natives.hpp")
    literal = re.search(r'kVersion\[\]\s*=\s*"([^"]+)"', natives)
    if literal:
        assert literal.group(1) == expected, "natives.hpp spells another version than the project"
        return
    assert re.search(r"kVersion\[\]\s*=\s*CB_VERSION\b", natives), "kVersion is neither a literal nor CB_VERSION"
    cmake = text("cp/plugin/CMakeLists.txt")
    for definition in ("CB_VERSION", "CB_VERSION_MAJOR", "CB_VERSION_MINOR", "CB_VERSION_PATCH"):
        assert definition in cmake, f"CMakeLists.txt does not define {definition}"
    assert cmake.count("CB_VERSION") >= 2  # Cyberblock and cb_tests


def test_the_mod_takes_its_version_from_gradle_properties():
    assert '"version": "${version}"' in text("mc/src/main/resources/fabric.mod.json")  # expanded by processResources, never hard-coded


def test_the_top_changelog_entry_is_the_current_version_and_planned_follows():
    changelog = text("CHANGELOG.md")
    entries = re.findall(r"^## (.+)$", changelog, re.M)
    assert entries[0].startswith(f"[{versions()['pyproject.toml']}]") and entries[-1] == "Planned"
    assert re.search(r"^### Added$", changelog, re.M) and "Keep a Changelog" in changelog


def test_documents_name_the_release_files_of_the_current_version():
    expected = versions()["pyproject.toml"]
    for rel in ("README.md", "docs/HOW-TO-PLAY.md"):
        body = text(rel)
        zips, jars = re.findall(r"Cyberblock-(\d+\.\d+\.\d+)\.zip", body), re.findall(r"cyberblock-(\d+\.\d+\.\d+)\.jar", body)
        assert zips and jars, f"{rel} names no release zip or jar"
        assert set(zips + jars) == {expected}, f"{rel} names another version than {expected}"


def test_the_ci_workflow_exists_and_runs_the_four_jobs_with_read_only_permissions():
    ci = text(".github/workflows/ci.yml")
    for job in ("python:", "mod:", "dll:", "package:"):
        assert re.search(rf"^  {job}", ci, re.M), f"no {job} job"
    assert re.search(r"^permissions:\s*\n\s+contents: read", ci, re.M)
    for needle in ("uv sync --locked", "uv run pytest -q", "git diff --exit-code", "wrapper-validation", "temurin", "gradlew.bat test build",
                   "cb_tests.exe", "ctest", "tools.package"):
        assert needle in ci, needle
    assert "windows-2022" in ci and "java-version: '25'" in ci


@pytest.mark.parametrize("path", [".github/workflows/ci.yml"])
def test_workflow_files_are_valid_yaml_without_tabs(path):
    body = text(path)
    assert "\t" not in body
    yaml = pytest.importorskip("yaml", reason="PyYAML is not a dependency; the structural checks above still run")
    assert isinstance(yaml.safe_load(body), dict)
