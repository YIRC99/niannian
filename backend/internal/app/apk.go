package app

import (
	"archive/zip"
	"encoding/binary"
	"errors"
	"io"
	"strconv"
	"unicode/utf16"
)

// Read the compiled manifest, never trust the upload filename or form version.
func apkVersion(path string) (code int64, name string, err error) {
	invalid := errors.New("请选择念念正式版 APK，且版本名称与版本号有效")
	z, e := zip.OpenReader(path)
	if e != nil {
		return 0, "", invalid
	}
	defer z.Close()
	var data []byte
	for _, f := range z.File {
		if f.Name == "AndroidManifest.xml" {
			if f.UncompressedSize64 > 2<<20 {
				return 0, "", invalid
			}
			in, e := f.Open()
			if e != nil {
				return 0, "", invalid
			}
			data, e = io.ReadAll(io.LimitReader(in, (2<<20)+1))
			in.Close()
			if e != nil || len(data) > 2<<20 {
				return 0, "", invalid
			}
			break
		}
	}
	if len(data) < 8 || binary.LittleEndian.Uint16(data) != 3 {
		return 0, "", invalid
	}
	u16 := func(b []byte, p int) int { return int(binary.LittleEndian.Uint16(b[p : p+2])) }
	u32 := func(b []byte, p int) uint32 { return binary.LittleEndian.Uint32(b[p : p+4]) }
	var strings []string
	str := func(i uint32) string {
		if uint64(i) >= uint64(len(strings)) {
			return ""
		}
		return strings[i]
	}
	for pos := 8; pos+8 <= len(data); {
		chunk := data[pos:]
		header, size := u16(chunk, 2), int(u32(chunk, 4))
		if header < 8 || size < header || size > len(chunk) {
			return 0, "", invalid
		}
		chunk = chunk[:size]
		switch u16(chunk, 0) {
		case 1:
			if header < 28 {
				return 0, "", invalid
			}
			count, start := int(u32(chunk, 8)), int(u32(chunk, 20))
			if count > 65536 || header+count*4 > size || start < header+count*4 || start > size {
				return 0, "", invalid
			}
			strings = make([]string, count)
			for i := 0; i < count; i++ {
				p := start + int(u32(chunk, header+i*4))
				if p < start || p >= size {
					return 0, "", invalid
				}
				if u32(chunk, 16)&0x100 != 0 {
					readLen := func() (int, bool) {
						if p >= size {
							return 0, false
						}
						v := int(chunk[p])
						p++
						if v&128 != 0 {
							if p >= size {
								return 0, false
							}
							v = ((v & 127) << 8) | int(chunk[p])
							p++
						}
						return v, true
					}
					if _, ok := readLen(); !ok {
						return 0, "", invalid
					}
					n, ok := readLen()
					if !ok || p+n >= size {
						return 0, "", invalid
					}
					strings[i] = string(chunk[p : p+n])
				} else {
					if p+2 > size {
						return 0, "", invalid
					}
					n := u16(chunk, p)
					p += 2
					if n&0x8000 != 0 {
						if p+2 > size {
							return 0, "", invalid
						}
						n = ((n & 0x7fff) << 16) | u16(chunk, p)
						p += 2
					}
					if n > (size-p)/2-1 {
						return 0, "", invalid
					}
					v := make([]uint16, n)
					for j := range v {
						v[j] = uint16(u16(chunk, p+j*2))
					}
					strings[i] = string(utf16.Decode(v))
				}
			}
		case 0x102:
			if header < 16 || header+20 > size {
				return 0, "", invalid
			}
			ext := header
			if str(u32(chunk, ext+4)) == "manifest" {
				start, width, count := u16(chunk, ext+8), u16(chunk, ext+10), u16(chunk, ext+12)
				if start < 20 || width < 20 || ext+start+width*count > size {
					return 0, "", invalid
				}
				pkg := ""
				for i := 0; i < count; i++ {
					p := ext + start + width*i
					key := str(u32(chunk, p+4))
					raw, kind, value := u32(chunk, p+8), chunk[p+15], u32(chunk, p+16)
					text := str(raw)
					if kind == 3 {
						text = str(value)
					}
					switch key {
					case "package":
						pkg = text
					case "versionName":
						name = text
					case "versionCode":
						if kind == 0x10 || kind == 0x11 {
							code = int64(value)
						} else {
							code, _ = strconv.ParseInt(text, 10, 64)
						}
					case "versionCodeMajor":
						if value != 0 {
							return 0, "", invalid
						}
					}
				}
				if pkg != "dev.niannian.reminder" || code < 1 || code > 2147483647 || name == "" || len(name) > 80 {
					return 0, "", invalid
				}
				return code, name, nil
			}
		}
		pos += size
	}
	return 0, "", invalid
}
