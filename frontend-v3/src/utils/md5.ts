/**
 * 精简 MD5 实现（RFC 1321）
 *
 * <h3>为什么需要</h3>
 * 后端登录采用「两次 MD5」校验（见 {@code UserServiceImpl.login}）：
 * <ol>
 *   <li>数据库 {@code sys_user.password} 存的是 {@code MD5(明文密码)}；</li>
 *   <li>前端提交的 password 是 {@code MD5(MD5(明文密码) + 随机码)}；</li>
 *   <li>后端比对 {@code MD5(数据库密码 + 随机码) == 前端提交值}。</li>
 * </ol>
 * Web Crypto API 只提供 SHA 系列、不含 MD5，故此处自带实现（纯函数、零依赖）。
 *
 * <p>处理 UTF-8 多字节字符与代理对，输出 32 位小写十六进制。</p>
 */

/** 每轮的循环左移位数 */
const SHIFT = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
]

/** K[i] = floor(2^32 × |sin(i + 1)|) */
const TABLE = Array.from({ length: 64 }, (_, i) =>
  Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296)
)

/** 字符串 → UTF-8 字节数组 */
function toUtf8Bytes(text: string): number[] {
  const bytes: number[] = []
  for (let i = 0; i < text.length; i += 1) {
    let code = text.charCodeAt(i)
    if (code < 0x80) {
      bytes.push(code)
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f))
    } else if (code >= 0xd800 && code <= 0xdfff) {
      // 代理区：合法代理对按 4 字节编码；孤立代理项按 Unicode 规范替换为 U+FFFD（EF BF BD）
      if (code <= 0xdbff && i + 1 < text.length) {
        const next = text.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          i += 1
          code = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00)
          bytes.push(
            0xf0 | (code >> 18),
            0x80 | ((code >> 12) & 0x3f),
            0x80 | ((code >> 6) & 0x3f),
            0x80 | (code & 0x3f)
          )
          continue
        }
      }
      // 孤立高代理 / 孤立低代理 → U+FFFD
      bytes.push(0xef, 0xbf, 0xbd)
    } else {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f))
    }
  }
  return bytes
}

/** 32 位循环左移 */
function rotateLeft(value: number, shift: number): number {
  return (value << shift) | (value >>> (32 - shift))
}

/** 32 位整数 → 小端十六进制（8 字符） */
function wordToHex(value: number): string {
  let hex = ''
  for (let i = 0; i < 4; i += 1) {
    const byte = (value >>> (8 * i)) & 0xff
    hex += byte.toString(16).padStart(2, '0')
  }
  return hex
}

/**
 * 计算 MD5
 *
 * @param text 待加密文本（按 UTF-8 编码）
 * @returns 32 位小写十六进制字符串
 */
export function md5(text: string): string {
  const bytes = toUtf8Bytes(text)
  const bitLength = bytes.length * 8

  // 填充：追加 0x80，再补 0x00 至长度 ≡ 56 (mod 64)
  bytes.push(0x80)
  while (bytes.length % 64 !== 56) {
    bytes.push(0)
  }
  // 追加 64 位小端比特长度（常规文本高 32 位为 0）
  for (let i = 0; i < 4; i += 1) {
    bytes.push((bitLength >>> (8 * i)) & 0xff)
  }
  bytes.push(0, 0, 0, 0)

  // 初始链接变量
  let a0 = 0x67452301
  let b0 = 0xefcdab89
  let c0 = 0x98badcfe
  let d0 = 0x10325476

  const words = new Array<number>(16)

  for (let offset = 0; offset < bytes.length; offset += 64) {
    for (let i = 0; i < 16; i += 1) {
      const base = offset + i * 4
      words[i] =
        bytes[base] |
        (bytes[base + 1] << 8) |
        (bytes[base + 2] << 16) |
        (bytes[base + 3] << 24)
    }

    let a = a0
    let b = b0
    let c = c0
    let d = d0

    for (let i = 0; i < 64; i += 1) {
      let f: number
      let g: number
      if (i < 16) {
        f = (b & c) | (~b & d)
        g = i
      } else if (i < 32) {
        f = (d & b) | (~d & c)
        g = (5 * i + 1) % 16
      } else if (i < 48) {
        f = b ^ c ^ d
        g = (3 * i + 5) % 16
      } else {
        f = c ^ (b | ~d)
        g = (7 * i) % 16
      }
      const temp = d
      d = c
      c = b
      const sum = (a + f + TABLE[i] + words[g]) | 0
      b = (b + rotateLeft(sum, SHIFT[i])) | 0
      a = temp
    }

    a0 = (a0 + a) | 0
    b0 = (b0 + b) | 0
    c0 = (c0 + c) | 0
    d0 = (d0 + d) | 0
  }

  return [a0, b0, c0, d0].map(wordToHex).join('')
}

/**
 * 登录密码加密：{@code MD5(MD5(明文密码) + 随机码)}
 *
 * <p>与后端 {@code UserServiceImpl.login} 的校验方式严格一致，
 * 改动此处需同步后端 {@code Md5Util}。</p>
 *
 * @param plainPassword 用户明文密码
 * @param randomCode    {@code POST /api/user/random-code} 返回的 4 位随机码
 */
export function encryptLoginPassword(plainPassword: string, randomCode: string): string {
  return md5(md5(plainPassword) + randomCode)
}
