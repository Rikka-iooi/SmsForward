"""Build a minimal GSM 7-bit SMS-DELIVER PDU for local receiver testing."""
import sys


def bcd_swap(digits: str) -> bytes:
    if len(digits) % 2:
        digits += "F"
    out = []
    for i in range(0, len(digits), 2):
        out.append(int(digits[i + 1] + digits[i], 16))
    return bytes(out)


def gsm7(text: str):
    table = {chr(65 + i): 65 + i for i in range(26)}
    table.update({chr(97 + i): 97 + i for i in range(26)})
    table.update({str(i): 48 + i for i in range(10)})
    table.update({" ": 32, "!": 33, '"': 34, "#": 35, "$": 36, "%": 37, "&": 38,
                  "'": 39, "(": 40, ")": 41, "*": 42, "+": 43, ",": 44, "-": 45,
                  ".": 46, "/": 47, ":": 58, ";": 59, "<": 60, "=": 61, ">": 62,
                  "?": 63})
    codes = [table[c] for c in text]
    buf = 0
    bits = 0
    out = []
    for code in codes:
        buf |= code << bits
        bits += 7
        while bits >= 8:
            out.append(buf & 0xFF)
            buf >>= 8
            bits -= 8
    if bits:
        out.append(buf & 0xFF)
    return bytes(out), len(codes)


def make_pdu(sender: str, text: str) -> bytes:
    smsc = b"\x00"
    addr = bcd_swap(sender)
    # 2026-11-08 19:54:00 +08:00
    timestamp = bcd_swap("261108195400") + bytes([0x32])
    user_data, septets = gsm7(text)
    return (smsc + bytes([0x04, len(sender), 0x81]) + addr + b"\x00\x00"
            + timestamp + bytes([septets]) + user_data)


if __name__ == "__main__":
    sender = sys.argv[1] if len(sys.argv) > 1 else "10086"
    text = sys.argv[2] if len(sys.argv) > 2 else "TestPDU"
    print(make_pdu(sender, text).hex())
