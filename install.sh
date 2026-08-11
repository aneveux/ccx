#!/usr/bin/env bash
set -euo pipefail

VERSION="${CCX_VERSION:-latest}"
INSTALL_DIR="${CCX_INSTALL_DIR:-${HOME}/.local/bin}"
REPO="aneveux/ccx"

# The release publishes a single linux/x86_64 native binary named `ccx` plus a
# `checksums_sha256.txt` sidecar, so both download URLs are deterministic once the
# tag is known. The sidecar name comes from JReleaser: the default `checksum.name`
# is `checksums.txt` and `getResolvedName(context, algorithm)` always inserts
# `_<algorithm>` before the extension, so a SHA-256 release yields
# `checksums_sha256.txt`. Do NOT pin `checksum.name` in the pom to that value —
# JReleaser would then emit `checksums_sha256_sha256.txt`.
if [ "$VERSION" = "latest" ]; then
	VERSION=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" |
		grep '"tag_name"' | head -n1 | cut -d'"' -f4)
	[ -n "$VERSION" ] || {
		echo "error: could not resolve the latest release tag." >&2
		exit 1
	}
fi

base="https://github.com/${REPO}/releases/download/${VERSION}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Installing ccx ${VERSION} to ${INSTALL_DIR}..."
curl -fsSL "${base}/ccx" -o "${tmp}/ccx"

if [ "${CCX_SKIP_CHECKSUM:-0}" = "1" ]; then
	echo "warning: CCX_SKIP_CHECKSUM=1 — skipping integrity verification." >&2
else
	curl -fsSL "${base}/checksums_sha256.txt" -o "${tmp}/checksums_sha256.txt"
	# Verify only the `ccx` line: the grouping file lists every artifact of every active
	# distribution, and `sha256sum -c` fails on any line whose file is absent locally.
	# `set -o pipefail` makes a non-matching grep fail the pipeline closed.
	(cd "$tmp" && grep ' ccx$' checksums_sha256.txt | sha256sum -c -) || {
		echo "error: checksum verification failed — the download may be corrupted or tampered with. Aborting." >&2
		exit 1
	}
fi

mkdir -p "${INSTALL_DIR}"
chmod +x "${tmp}/ccx"
mv "${tmp}/ccx" "${INSTALL_DIR}/ccx"

echo "Done. Run: ccx --help"
echo "Ensure ${INSTALL_DIR} is on your \$PATH (e.g. add 'export PATH=\"\$HOME/.local/bin:\$PATH\"' to your shell profile)."
