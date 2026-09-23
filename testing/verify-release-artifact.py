#!/usr/bin/env python3
"""Reads a release APK's identity out of the artifact itself, with no SDK.

The release gates - `verify-release-signer.sh`, `verify-release-apk.sh` - run on
the runner, so a local check that only reads CI's log proves nothing the runner
did not already claim. Everything that matters is in the file:

* **Who signed it.** The APK Signing Block sits immediately before the ZIP
  central directory; its v2 block (id 0x7109871a) carries the certificates, and
  the first one's SHA-256 must equal `EXPECTED_SIGNER` - the same constant
  `verify-release-signer.sh` holds, which is why the two agreeing is evidence
  and only one of them running is not. A checksum against the release's
  `SHA256SUMS.txt` proves the bytes are the ones CI hashed and says nothing
  about the key: v1.5.0 shipped a regenerated keystore and every install
  answered INSTALL_FAILED_UPDATE_INCOMPATIBLE. See AUDIT-REPORT.md section 56.
* **What version it is.** `AndroidManifest.xml` inside the APK is binary XML.
  The chunk type is a u16 followed by a u16 header size, so the first four
  bytes read as a u32 are 0x00080003 and never the RES_XML_TYPE a naive parser
  expects; and versionCode/versionName are found by their resource ids
  (0x0101021b / 0x0101021c) in the resource-map chunk rather than by position.

Usage:
  testing/verify-release-artifact.py <apk> [<apk> ...]
  testing/verify-release-artifact.py --expect-name 1.8.2 --expect-code 41 <apk>

With no --expect-* the file is only reported, which is what calibrating the
checker against a previous release looks like: run it against a published asset
whose values are already known (v1.8.1 must read 40 / 1.8.1 and the canonical
digest) before believing anything it says about a new one. A checker that has
never disagreed with reality is not evidence.
"""

from __future__ import annotations

import hashlib
import struct
import sys
import zipfile

# The certificate every release from v1.1.20 through v1.8.1 carries. Rotating
# the release key is a deliberate act that has to edit this constant and
# `testing/verify-release-signer.sh` in the same commit, with the reinstall it
# costs stated in the release notes. It is a tripwire, not a default.
EXPECTED_SIGNER = "0c69794b7934452bdf1b2f1f345314e13b86ef43f0d17bec9ff260dd30a443be"

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_ELEMENT_TYPE = 0x0102

TYPE_STRING = 0x03

RES_VERSION_CODE = 0x0101021B
RES_VERSION_NAME = 0x0101021C


class NotAnApk(Exception):
    """The file is not shaped like an APK this checker can read."""


def _u16(data: bytes, at: int) -> int:
    return struct.unpack_from("<H", data, at)[0]


def _u32(data: bytes, at: int) -> int:
    return struct.unpack_from("<I", data, at)[0]


def _u64(data: bytes, at: int) -> int:
    return struct.unpack_from("<Q", data, at)[0]


def signer_sha256(apk: bytes) -> str:
    """The SHA-256 of the first certificate in the APK's v2 signing block."""
    # The ZIP End Of Central Directory record is the last thing in the file
    # before an optional comment; scan back for its signature rather than
    # assuming it is the final 22 bytes.
    eocd = apk.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise NotAnApk("no ZIP end-of-central-directory record")
    cd_offset = _u32(apk, eocd + 16)

    if apk[cd_offset - 16:cd_offset] != APK_SIG_BLOCK_MAGIC:
        raise NotAnApk("no APK signing block before the central directory")
    size = _u64(apk, cd_offset - 24)
    start = cd_offset - size

    # Walk the id-value pairs and require them to land exactly on the size
    # field that precedes the magic. A wrong `start` usually still decodes as
    # *something*; only the boundary check says it decoded the right thing.
    pos = start
    end = cd_offset - 24
    pairs: dict[int, bytes] = {}
    while pos < end:
        length = _u64(apk, pos)
        pair_id = _u32(apk, pos + 8)
        value = apk[pos + 12:pos + 8 + length]
        if len(value) != length - 4:
            raise NotAnApk("a signing-block pair runs past its length")
        pairs[pair_id] = value
        pos += 8 + length
    if pos != end:
        raise NotAnApk("the signing block's pairs do not fill the block")

    if V2_BLOCK_ID not in pairs:
        raise NotAnApk("no v2 signature block (id 0x7109871a)")

    # v2 value: a length-prefixed sequence of length-prefixed signers; each
    # signer is a length-prefixed signed data, then signatures, then public key;
    # signed data is a length-prefixed digests, then certificates, then
    # additional attributes. Two length prefixes stand between the block and the
    # signed data - the sequence's and the signer's - and dropping one still
    # decodes as *something*, which is how the first version of this function
    # read a published release as a 264-byte "certificate". The DER tag check
    # below is what makes that class of mistake fail instead of report.
    block = pairs[V2_BLOCK_ID]
    signers_len = _u32(block, 0)
    signers = block[4:4 + signers_len]
    if len(signers) < 4:
        raise NotAnApk("the v2 block carries no signers")
    signer_len = _u32(signers, 0)
    signer = signers[4:4 + signer_len]
    signed_data_len = _u32(signer, 0)
    signed_data = signer[4:4 + signed_data_len]
    digests_len = _u32(signed_data, 0)
    after_digests = 4 + digests_len
    certs_len = _u32(signed_data, after_digests)
    certs = signed_data[after_digests + 4:after_digests + 4 + certs_len]
    first_cert_len = _u32(certs, 0)
    first_cert = certs[4:4 + first_cert_len]
    if not first_cert or first_cert[0] != 0x30:
        raise NotAnApk("the v2 block's first certificate is not DER")
    return hashlib.sha256(first_cert).hexdigest()


def _string_pool(data: bytes, at: int) -> list[str]:
    """The strings of a RES_STRING_POOL chunk starting at `at`."""
    header_size = _u16(data, at + 2)
    count = _u32(data, at + 8)
    flags = _u32(data, at + 16)
    strings_start = _u32(data, at + 20) + at
    utf8 = bool(flags & 0x100)
    out: list[str] = []
    for i in range(count):
        offset = _u32(data, at + header_size + i * 4) + strings_start
        if utf8:
            # UTF-8 pools carry two lengths: characters, then bytes. Both are
            # 1 byte, or 2 when the high bit of the first is set.
            n = data[offset]
            offset += 2 if n & 0x80 else 1
            n = data[offset]
            offset += 2 if n & 0x80 else 1
            length = data[offset]
            offset += 2 if length & 0x80 else 1
            out.append(data[offset:offset + length].decode("utf-8", "replace"))
        else:
            length = _u16(data, offset)
            offset += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | _u16(data, offset)
                offset += 2
            out.append(data[offset:offset + length * 2].decode("utf-16-le", "replace"))
    return out


def manifest_identity(manifest: bytes) -> dict[str, str]:
    """package, versionCode and versionName from a binary-XML manifest."""
    if _u16(manifest, 0) != RES_XML_TYPE:
        raise NotAnApk("the manifest is not binary XML")
    chunk_size = _u32(manifest, 4)

    strings: list[str] = []
    # Resource ids, one per leading string-pool entry: index i names string i.
    res_map: dict[int, int] = {}
    found: dict[str, str] = {}

    at = _u16(manifest, 2)
    while at < chunk_size:
        chunk_type = _u16(manifest, at)
        size = _u32(manifest, at + 4)
        if size == 0:
            raise NotAnApk("a manifest chunk declares zero length")
        if chunk_type == RES_STRING_POOL_TYPE:
            strings = _string_pool(manifest, at)
        elif chunk_type == RES_XML_RESOURCE_MAP_TYPE:
            count = (size - _u16(manifest, at + 2)) // 4
            for i in range(count):
                res_map[i] = _u32(manifest, at + _u16(manifest, at + 2) + i * 4)
        elif chunk_type == RES_XML_START_ELEMENT_TYPE:
            name = strings[_u32(manifest, at + 20)]
            attr_start = _u16(manifest, at + 24)
            attr_size = _u16(manifest, at + 26)
            attr_count = _u16(manifest, at + 28)
            for i in range(attr_count):
                a = at + 16 + attr_start + i * attr_size
                raw_name = _u32(manifest, a + 4)
                attr_res = res_map.get(raw_name)
                data_type = manifest[a + 15]
                value = _u32(manifest, a + 16)
                if data_type == TYPE_STRING:
                    text = strings[value]
                else:
                    text = str(value)
                if attr_res == RES_VERSION_CODE:
                    found["versionCode"] = str(value)
                elif attr_res == RES_VERSION_NAME:
                    found["versionName"] = text
                elif not attr_res and strings[raw_name] == "package":
                    found["package"] = text
            if name == "manifest":
                break
        at += size

    if "package" not in found:
        raise NotAnApk("the manifest names no package")
    return found


def main(argv: list[str]) -> int:
    expect_name = expect_code = None
    paths: list[str] = []
    i = 0
    while i < len(argv):
        if argv[i] == "--expect-name":
            expect_name = argv[i + 1]
            i += 2
        elif argv[i] == "--expect-code":
            expect_code = argv[i + 1]
            i += 2
        else:
            paths.append(argv[i])
            i += 1
    if not paths:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2

    failed = False
    for path in paths:
        try:
            with open(path, "rb") as handle:
                apk = handle.read()
            digest = signer_sha256(apk)
            with zipfile.ZipFile(path) as archive:
                identity = manifest_identity(archive.read("AndroidManifest.xml"))
        except (OSError, KeyError, NotAnApk, struct.error) as exc:
            print(f"FAIL {path}: {exc}")
            failed = True
            continue

        problems = []
        if identity["package"] != "dev.eclipse.ssh":
            problems.append(f"package is {identity['package']}")
        if expect_name is not None and identity["versionName"] != expect_name:
            problems.append(
                f"versionName is {identity['versionName']}, expected {expect_name}"
            )
        if expect_code is not None and identity["versionCode"] != expect_code:
            problems.append(
                f"versionCode is {identity['versionCode']}, expected {expect_code}"
            )
        if digest != EXPECTED_SIGNER:
            problems.append(f"signed by {digest}, expected {EXPECTED_SIGNER}")

        line = (
            f"{path}: versionCode={identity['versionCode']} "
            f"versionName={identity['versionName']} signer={digest[:8]}…"
        )
        if problems:
            failed = True
            print(f"FAIL {line}")
            for problem in problems:
                print(f"     {problem}")
        else:
            print(f"ok   {line}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
