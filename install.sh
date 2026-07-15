#!/usr/bin/env bash
set -euo pipefail

VERSION="${CCX_VERSION:-latest}"
INSTALL_DIR="${CCX_INSTALL_DIR:-${HOME}/.local/bin}"
REPO="aneveux/ccx"

api_release_url() {
	if [ "$VERSION" = "latest" ]; then
		echo "https://api.github.com/repos/${REPO}/releases/latest"
	else
		echo "https://api.github.com/repos/${REPO}/releases/tags/${VERSION}"
	fi
}

# Discover the linux/x86_64 asset's download URL directly from the release
# metadata rather than guessing its name — JReleaser's asset naming for a
# NATIVE_IMAGE distribution is not guaranteed to match a fixed string.
asset_download_url() {
	local release_json="$1"
	echo "$release_json" |
		grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*"' |
		cut -d'"' -f4 |
		grep -Ei 'linux[._-]?(x86_64|amd64|x64)' |
		head -n1
}

# Discover the SHA-256 checksums asset URL (JReleaser emits checksums_sha256.txt
# or checksums.txt). Prefer the sha256-specific file when both are present.
checksums_download_url() {
	local release_json="$1" candidates sha256
	candidates=$(echo "$release_json" |
		grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*"' |
		cut -d'"' -f4 |
		grep -Ei 'checksums.*\.txt$')
	sha256=$(echo "$candidates" | grep -i 'sha256' | head -n1)
	if [ -n "$sha256" ]; then
		echo "$sha256"
	else
		echo "$candidates" | head -n1
	fi
}

# Fail-closed SHA-256 verification: the binary is never made executable unless its
# digest matches the value published in the release's checksums file. Guards the
# curl|bash path against a tampered/MITM'd asset.
verify_checksum() {
	local file="$1" expected_url="$2" checksums asset_name expected actual
	asset_name="${file##*/}"

	checksums=$(curl -fsSL "$expected_url" 2>/dev/null) || {
		echo "error: could not download checksums file for integrity verification." >&2
		echo "       Refusing to install an unverified binary. Set CCX_SKIP_CHECKSUM=1 to override (not recommended)." >&2
		exit 1
	}

	expected=$(echo "$checksums" | grep -E "[[:space:]]\*?${asset_name}\$" | head -n1 | awk '{print $1}')
	if [ -z "$expected" ]; then
		echo "error: no checksum entry for ${asset_name} in the release checksums file." >&2
		echo "       Refusing to install an unverified binary. Set CCX_SKIP_CHECKSUM=1 to override (not recommended)." >&2
		exit 1
	fi

	actual=$(sha256sum "$file" | awk '{print $1}')
	if [ "$expected" != "$actual" ]; then
		echo "error: checksum mismatch for ${asset_name}." >&2
		echo "       expected: ${expected}" >&2
		echo "       actual:   ${actual}" >&2
		echo "       The download may be corrupted or tampered with. Aborting." >&2
		exit 1
	fi
	echo "Verified SHA-256 checksum for ${asset_name}."
}

main() {
	local release_json url asset tmp checksums_url
	release_json=$(curl -fsSL "$(api_release_url)")

	if [ "$VERSION" = "latest" ]; then
		VERSION=$(echo "$release_json" | grep '"tag_name"' | head -n1 | cut -d'"' -f4)
	fi

	url=$(asset_download_url "$release_json")
	if [ -z "$url" ]; then
		echo "error: no linux/x86_64 asset found in release ${VERSION}." >&2
		echo "       See https://github.com/${REPO}/releases/${VERSION} for available downloads." >&2
		exit 1
	fi
	asset="${url##*/}"

	echo "Installing ccx ${VERSION} to ${INSTALL_DIR}..."
	mkdir -p "${INSTALL_DIR}"

	tmp=$(mktemp -d)
	trap 'rm -rf "$tmp"' EXIT

	curl -fsSL "$url" -o "${tmp}/${asset}"

	# Verify integrity before the asset is ever extracted or made executable.
	if [ "${CCX_SKIP_CHECKSUM:-0}" = "1" ]; then
		echo "warning: CCX_SKIP_CHECKSUM=1 — skipping integrity verification." >&2
	else
		checksums_url=$(checksums_download_url "$release_json")
		if [ -z "$checksums_url" ]; then
			echo "error: release ${VERSION} publishes no checksums file — cannot verify integrity." >&2
			echo "       Refusing to install an unverified binary. Set CCX_SKIP_CHECKSUM=1 to override (not recommended)." >&2
			exit 1
		fi
		verify_checksum "${tmp}/${asset}" "$checksums_url"
	fi

	case "$asset" in
	*.tar.gz | *.tgz)
		tar -xzf "${tmp}/${asset}" -C "$tmp"
		;;
	*.zip)
		unzip -q "${tmp}/${asset}" -d "$tmp"
		;;
	esac

	# Locate the executable: the downloaded file itself if it is a raw binary,
	# otherwise the extracted ccx/ccx-runner binary from the archive.
	local binary
	case "$asset" in
	*.tar.gz | *.tgz | *.zip)
		binary=$(find "$tmp" -type f \( -name 'ccx' -o -name 'ccx-runner' \) | head -n1)
		;;
	*)
		binary="${tmp}/${asset}"
		;;
	esac

	if [ -z "${binary:-}" ] || [ ! -f "$binary" ]; then
		echo "error: could not locate the ccx binary inside ${asset}." >&2
		exit 1
	fi

	chmod +x "$binary"
	mv "$binary" "${INSTALL_DIR}/ccx"
	echo "Done. Run: ccx --help"
	echo "Ensure ${INSTALL_DIR} is on your \$PATH (e.g. add 'export PATH=\"\$HOME/.local/bin:\$PATH\"' to your shell profile)."
}

main "$@"
