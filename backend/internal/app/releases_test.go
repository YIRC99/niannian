package app

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"fmt"
	"mime/multipart"
	"net/http/httptest"
	"os"
	"testing"
)

func TestRealReleaseAPK(t *testing.T) {
	path := os.Getenv("NIANNIAN_TEST_APK")
	if path == "" {
		t.Skip("set NIANNIAN_TEST_APK for built APK verification")
	}
	code, name, err := apkVersion(path)
	if err != nil || code != 34 || name != "2.6.0" {
		t.Fatalf("real APK metadata: %d %q %v", code, name, err)
	}
	a := adminSetup(t)
	apk, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var body bytes.Buffer
	m := multipart.NewWriter(&body)
	m.WriteField("notes", "真实构建产物验证")
	f, _ := m.CreateFormFile("file", "niannian.apk")
	f.Write(apk)
	m.Close()
	r := httptest.NewRequest("POST", "/admin/releases", &body)
	r.Header.Set("Authorization", "Bearer "+testAdminKey)
	r.Header.Set("Content-Type", m.FormDataContentType())
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	if w.Code != 201 {
		t.Fatalf("real upload %d %s", w.Code, w.Body.String())
	}
	_, out := request(t, a, "GET", "/api/v1/updates/latest?version_code=33", "", nil)
	v := out["release"].(map[string]any)
	w = httptest.NewRecorder()
	a.Handler().ServeHTTP(w, httptest.NewRequest("GET", v["download_path"].(string), nil))
	if w.Code != 200 || !bytes.Equal(apk, w.Body.Bytes()) {
		t.Fatal("real APK download bytes differ")
	}
}

func TestAPKRejectsMalformedFiles(t *testing.T) {
	a := adminSetup(t)
	for _, payload := range [][]byte{[]byte("not an APK"), testAPK(t, 0), testAPK(t, 0xffffffff)} {
		var body bytes.Buffer
		m := multipart.NewWriter(&body)
		f, _ := m.CreateFormFile("file", "bad.apk")
		f.Write(payload)
		m.Close()
		r := httptest.NewRequest("POST", "/admin/releases", &body)
		r.Header.Set("Authorization", "Bearer "+testAdminKey)
		r.Header.Set("Content-Type", m.FormDataContentType())
		w := httptest.NewRecorder()
		a.Handler().ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatalf("invalid APK: %d %s", w.Code, w.Body.String())
		}
	}
}

// A minimal binary Android manifest with the same typed attributes as aapt.
func testAPK(t *testing.T, code uint32) []byte {
	t.Helper()
	strings := []string{"manifest", "package", "versionCode", "versionName", "dev.niannian.reminder", "2.6.0"}
	pool := new(bytes.Buffer)
	offsets := []uint32{}
	for _, s := range strings {
		offsets = append(offsets, uint32(pool.Len()))
		pool.WriteByte(byte(len(s)))
		pool.WriteByte(byte(len(s)))
		pool.WriteString(s)
		pool.WriteByte(0)
	}
	for pool.Len()%4 != 0 {
		pool.WriteByte(0)
	}
	out := new(bytes.Buffer)
	put := func(v any) {
		if err := binary.Write(out, binary.LittleEndian, v); err != nil {
			t.Fatal(err)
		}
	}
	put(uint16(3))
	put(uint16(8))
	put(uint32(0))
	put(uint16(1))
	put(uint16(28))
	put(uint32(28 + 4*len(strings) + pool.Len()))
	put(uint32(len(strings)))
	put(uint32(0))
	put(uint32(0x100))
	put(uint32(28 + 4*len(strings)))
	put(uint32(0))
	for _, o := range offsets {
		put(o)
	}
	out.Write(pool.Bytes())
	put(uint16(0x102))
	put(uint16(16))
	put(uint32(96))
	put(uint32(1))
	put(uint32(0xffffffff))
	put(uint32(0xffffffff))
	put(uint32(0))
	put(uint16(20))
	put(uint16(20))
	put(uint16(3))
	put(uint16(0))
	put(uint16(0))
	put(uint16(0))
	attr := func(name, raw uint32, kind byte, value uint32) {
		put(uint32(0xffffffff))
		put(name)
		put(raw)
		put(uint16(8))
		put(byte(0))
		put(kind)
		put(value)
	}
	attr(1, 4, 3, 4)
	attr(2, 0xffffffff, 0x10, code)
	attr(3, 5, 3, 5)
	b := out.Bytes()
	binary.LittleEndian.PutUint32(b[4:8], uint32(len(b)))
	apk := new(bytes.Buffer)
	z := zip.NewWriter(apk)
	f, _ := z.Create("AndroidManifest.xml")
	f.Write(b)
	f, _ = z.Create("classes.dex")
	f.Write([]byte("dex\n"))
	z.Close()
	return apk.Bytes()
}
func uploadTestRelease(t *testing.T, a *App, code uint32) (int, string) {
	t.Helper()
	var body bytes.Buffer
	m := multipart.NewWriter(&body)
	m.WriteField("notes", "修复与改进")
	f, _ := m.CreateFormFile("file", "niannian.apk")
	f.Write(testAPK(t, code))
	m.Close()
	r := httptest.NewRequest("POST", "/admin/releases", &body)
	r.Header.Set("Authorization", "Bearer "+testAdminKey)
	r.Header.Set("Content-Type", m.FormDataContentType())
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	return w.Code, w.Body.String()
}
func TestReleasePublicationDownloadAndWithdrawal(t *testing.T) {
	a := adminSetup(t)
	code, body := uploadTestRelease(t, a, 34)
	if code != 201 {
		t.Fatalf("upload %d %s", code, body)
	}
	code, out := request(t, a, "GET", "/api/v1/updates/latest?version_code=33", "", nil)
	if code != 200 || out["available"] != true {
		t.Fatal(code, out)
	}
	release := out["release"].(map[string]any)
	id := release["id"].(string)
	r := httptest.NewRequest("GET", fmt.Sprint(release["download_path"]), nil)
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), testAPK(t, 34)) {
		t.Fatal("download mismatch", w.Code)
	}
	code, out = request(t, a, "GET", "/api/v1/updates/latest?version_code=34", "", nil)
	if code != 200 || out["available"] != false {
		t.Fatal(code, out)
	}
	code, body = uploadTestRelease(t, a, 34)
	if code != 409 {
		t.Fatal("duplicate release", code, body)
	}
	code, out = request(t, a, "PUT", "/admin/releases/"+id, testAdminKey, map[string]bool{"published": false})
	if code != 200 {
		t.Fatal(code, out)
	}
	code, out = request(t, a, "GET", "/api/v1/updates/latest?version_code=33", "", nil)
	if code != 200 || out["available"] != false {
		t.Fatal(code, out)
	}
	w = httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	if w.Code != 404 {
		t.Fatal("withdrawn download", w.Code)
	}
}
