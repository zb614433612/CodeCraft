/**
 * MD5加密函数
 * 基于原生的JavaScript实现，参考自：https://github.com/blueimp/JavaScript-MD5
 * 适用于简单的MD5加密需求
 */

// MD5函数
export function md5(string: string): string {
  function rotateLeft(lValue: number, iShiftBits: number): number {
    return (lValue << iShiftBits) | (lValue >>> (32 - iShiftBits))
  }

  function addUnsigned(lX: number, lY: number): number {
    let lX8 = lX & 0x80000000
    let lY8 = lY & 0x80000000
    let lX4 = lX & 0x40000000
    let lY4 = lY & 0x40000000
    let lResult = (lX & 0x3fffffff) + (lY & 0x3fffffff)
    if (lX4 & lY4) {
      return lResult ^ 0x80000000 ^ lX8 ^ lY8
    }
    if (lX4 | lY4) {
      if (lResult & 0x40000000) {
        return lResult ^ 0xc0000000 ^ lX8 ^ lY8
      } else {
        return lResult ^ 0x40000000 ^ lX8 ^ lY8
      }
    } else {
      return lResult ^ lX8 ^ lY8
    }
  }

  function f(x: number, y: number, z: number): number {
    return (x & y) | (~x & z)
  }

  function g(x: number, y: number, z: number): number {
    return (x & z) | (y & ~z)
  }

  function h(x: number, y: number, z: number): number {
    return x ^ y ^ z
  }

  function i(x: number, y: number, z: number): number {
    return y ^ (x | ~z)
  }

  function ff(a: number, b: number, c: number, d: number, x: number, s: number, ac: number): number {
    a = addUnsigned(a, addUnsigned(addUnsigned(f(b, c, d), x), ac))
    return addUnsigned(rotateLeft(a, s), b)
  }

  function gg(a: number, b: number, c: number, d: number, x: number, s: number, ac: number): number {
    a = addUnsigned(a, addUnsigned(addUnsigned(g(b, c, d), x), ac))
    return addUnsigned(rotateLeft(a, s), b)
  }

  function hh(a: number, b: number, c: number, d: number, x: number, s: number, ac: number): number {
    a = addUnsigned(a, addUnsigned(addUnsigned(h(b, c, d), x), ac))
    return addUnsigned(rotateLeft(a, s), b)
  }

  function ii(a: number, b: number, c: number, d: number, x: number, s: number, ac: number): number {
    a = addUnsigned(a, addUnsigned(addUnsigned(i(b, c, d), x), ac))
    return addUnsigned(rotateLeft(a, s), b)
  }

  function convertToWordArray(string: string): number[] {
    let lWordCount
    const lMessageLength = string.length
    const lNumberOfWordsTemp1 = lMessageLength + 8
    const lNumberOfWordsTemp2 = (lNumberOfWordsTemp1 - (lNumberOfWordsTemp1 % 64)) / 64
    const lNumberOfWords = (lNumberOfWordsTemp2 + 1) * 16
    const lWordArray: number[] = Array(lNumberOfWords - 1)
    let lBytePosition = 0
    let lByteCount = 0
    while (lByteCount < lMessageLength) {
      lWordCount = (lByteCount - (lByteCount % 4)) / 4
      lBytePosition = (lByteCount % 4) * 8
      lWordArray[lWordCount] = (lWordArray[lWordCount] || 0) | (string.charCodeAt(lByteCount) << lBytePosition)
      lByteCount++
    }
    lWordCount = (lByteCount - (lByteCount % 4)) / 4
    lBytePosition = (lByteCount % 4) * 8
    lWordArray[lWordCount] = lWordArray[lWordCount] | (0x80 << lBytePosition)
    lWordArray[lNumberOfWords - 2] = lMessageLength << 3
    lWordArray[lNumberOfWords - 1] = lMessageLength >>> 29
    return lWordArray
  }

  function wordToHex(lValue: number): string {
    let wordToHexValue = ''
    let wordToHexValueTemp = ''
    let lByte
    let lCount
    for (lCount = 0; lCount <= 3; lCount++) {
      lByte = (lValue >>> (lCount * 8)) & 255
      wordToHexValueTemp = '0' + lByte.toString(16)
      wordToHexValue = wordToHexValue + wordToHexValueTemp.substr(wordToHexValueTemp.length - 2, 2)
    }
    return wordToHexValue
  }

  function utf8Encode(string: string): string {
    string = string.replace(/\r\n/g, '\n')
    let utftext = ''
    for (let n = 0; n < string.length; n++) {
      const c = string.charCodeAt(n)
      if (c < 128) {
        utftext += String.fromCharCode(c)
      } else if (c > 127 && c < 2048) {
        utftext += String.fromCharCode((c >> 6) | 192)
        utftext += String.fromCharCode((c & 63) | 128)
      } else {
        utftext += String.fromCharCode((c >> 12) | 224)
        utftext += String.fromCharCode(((c >> 6) & 63) | 128)
        utftext += String.fromCharCode((c & 63) | 128)
      }
    }
    return utftext
  }

  let x: number[] = []
  let k
  let AA
  let BB
  let CC
  let DD
  let a
  let b
  let c
  let d
  const S11 = 7
  const S12 = 12
  const S13 = 17
  const S14 = 22
  const S21 = 5
  const S22 = 9
  const S23 = 14
  const S24 = 20
  const S31 = 4
  const S32 = 11
  const S33 = 16
  const S34 = 23
  const S41 = 6
  const S42 = 10
  const S43 = 15
  const S44 = 21

  string = utf8Encode(string)

  x = convertToWordArray(string)

  a = 0x67452301
  b = 0xefcdab89
  c = 0x98badcfe
  d = 0x10325476

  for (k = 0; k < x.length; k += 16) {
    AA = a
    BB = b
    CC = c
    DD = d
    a = ff(a, b, c, d, x[k], S11, 0xd76aa478)
    d = ff(d, a, b, c, x[k + 1], S12, 0xe8c7b756)
    c = ff(c, d, a, b, x[k + 2], S13, 0x242070db)
    b = ff(b, c, d, a, x[k + 3], S14, 0xc1bdceee)
    a = ff(a, b, c, d, x[k + 4], S11, 0xf57c0faf)
    d = ff(d, a, b, c, x[k + 5], S12, 0x4787c62a)
    c = ff(c, d, a, b, x[k + 6], S13, 0xa8304613)
    b = ff(b, c, d, a, x[k + 7], S14, 0xfd469501)
    a = ff(a, b, c, d, x[k + 8], S11, 0x698098d8)
    d = ff(d, a, b, c, x[k + 9], S12, 0x8b44f7af)
    c = ff(c, d, a, b, x[k + 10], S13, 0xffff5bb1)
    b = ff(b, c, d, a, x[k + 11], S14, 0x895cd7be)
    a = ff(a, b, c, d, x[k + 12], S11, 0x6b901122)
    d = ff(d, a, b, c, x[k + 13], S12, 0xfd987193)
    c = ff(c, d, a, b, x[k + 14], S13, 0xa679438e)
    b = ff(b, c, d, a, x[k + 15], S14, 0x49b40821)
    a = gg(a, b, c, d, x[k + 1], S21, 0xf61e2562)
    d = gg(d, a, b, c, x[k + 6], S22, 0xc040b340)
    c = gg(c, d, a, b, x[k + 11], S23, 0x265e5a51)
    b = gg(b, c, d, a, x[k], S24, 0xe9b6c7aa)
    a = gg(a, b, c, d, x[k + 5], S21, 0xd62f105d)
    d = gg(d, a, b, c, x[k + 10], S22, 0x2441453)
    c = gg(c, d, a, b, x[k + 15], S23, 0xd8a1e681)
    b = gg(b, c, d, a, x[k + 4], S24, 0xe7d3fbc8)
    a = gg(a, b, c, d, x[k + 9], S21, 0x21e1cde6)
    d = gg(d, a, b, c, x[k + 14], S22, 0xc33707d6)
    c = gg(c, d, a, b, x[k + 3], S23, 0xf4d50d87)
    b = gg(b, c, d, a, x[k + 8], S24, 0x455a14ed)
    a = gg(a, b, c, d, x[k + 13], S21, 0xa9e3e905)
    d = gg(d, a, b, c, x[k + 2], S22, 0xfcefa3f8)
    c = gg(c, d, a, b, x[k + 7], S23, 0x676f02d9)
    b = gg(b, c, d, a, x[k + 12], S24, 0x8d2a4c8a)
    a = hh(a, b, c, d, x[k + 5], S31, 0xfffa3942)
    d = hh(d, a, b, c, x[k + 8], S32, 0x8771f681)
    c = hh(c, d, a, b, x[k + 11], S33, 0x6d9d6122)
    b = hh(b, c, d, a, x[k + 14], S34, 0xfde5380c)
    a = hh(a, b, c, d, x[k + 1], S31, 0xa4beea44)
    d = hh(d, a, b, c, x[k + 4], S32, 0x4bdecfa9)
    c = hh(c, d, a, b, x[k + 7], S33, 0xf6bb4b60)
    b = hh(b, c, d, a, x[k + 10], S34, 0xbebfbc70)
    a = hh(a, b, c, d, x[k + 13], S31, 0x289b7ec6)
    d = hh(d, a, b, c, x[k], S32, 0xeaa127fa)
    c = hh(c, d, a, b, x[k + 3], S33, 0xd4ef3085)
    b = hh(b, c, d, a, x[k + 6], S34, 0x4881d05)
    a = hh(a, b, c, d, x[k + 9], S31, 0xd9d4d039)
    d = hh(d, a, b, c, x[k + 12], S32, 0xe6db99e5)
    c = hh(c, d, a, b, x[k + 15], S33, 0x1fa27cf8)
    b = hh(b, c, d, a, x[k + 2], S34, 0xc4ac5665)
    a = ii(a, b, c, d, x[k], S41, 0xf4292244)
    d = ii(d, a, b, c, x[k + 7], S42, 0x432aff97)
    c = ii(c, d, a, b, x[k + 14], S43, 0xab9423a7)
    b = ii(b, c, d, a, x[k + 5], S44, 0xfc93a039)
    a = ii(a, b, c, d, x[k + 12], S41, 0x655b59c3)
    d = ii(d, a, b, c, x[k + 3], S42, 0x8f0ccc92)
    c = ii(c, d, a, b, x[k + 10], S43, 0xffeff47d)
    b = ii(b, c, d, a, x[k + 1], S44, 0x85845dd1)
    a = ii(a, b, c, d, x[k + 8], S41, 0x6fa87e4f)
    d = ii(d, a, b, c, x[k + 15], S42, 0xfe2ce6e0)
    c = ii(c, d, a, b, x[k + 6], S43, 0xa3014314)
    b = ii(b, c, d, a, x[k + 13], S44, 0x4e0811a1)
    a = ii(a, b, c, d, x[k + 4], S41, 0xf7537e82)
    d = ii(d, a, b, c, x[k + 11], S42, 0xbd3af235)
    c = ii(c, d, a, b, x[k + 2], S43, 0x2ad7d2bb)
    b = ii(b, c, d, a, x[k + 9], S44, 0xeb86d391)
    a = addUnsigned(a, AA)
    b = addUnsigned(b, BB)
    c = addUnsigned(c, CC)
    d = addUnsigned(d, DD)
  }

  const temp = wordToHex(a) + wordToHex(b) + wordToHex(c) + wordToHex(d)
  return temp.toLowerCase()
}

// 双重MD5加密函数
export function doubleMd5(password: string, randomCode: string): string {
  // 第一次MD5加密明文密码
  const firstMd5 = md5(password)
  // 将随机码拼接到第一次加密结果后面
  const combined = firstMd5 + randomCode
  // 第二次MD5加密
  const secondMd5 = md5(combined)
  return secondMd5
}