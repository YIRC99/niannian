// Package existing artwork at email display resolution; preserve transparency.
// Run from backend: go run ../scripts/package-mail-assets.go ..
package main

import (
	"image"
	"image/png"
	"os"
	"path/filepath"

	"golang.org/x/image/draw"
)

func main() {
	root := os.Args[1]
	out := filepath.Join(root, "backend/internal/mail/assets")
	if err := os.MkdirAll(out, 0755); err != nil {
		panic(err)
	}
	for _, asset := range []struct {
		source, name string
		width        int
	}{
		{"calendar_mail.png", "letter.png", 368},
		{"sowing_footer_foliage.png", "garden.png", 1120},
	} {
		f, err := os.Open(filepath.Join(root, "android/app/src/main/res/drawable-nodpi", asset.source))
		if err != nil {
			panic(err)
		}
		src, err := png.Decode(f)
		f.Close()
		if err != nil {
			panic(err)
		}
		b := src.Bounds()
		dst := image.NewNRGBA(image.Rect(0, 0, asset.width, b.Dy()*asset.width/b.Dx()))
		draw.CatmullRom.Scale(dst, dst.Bounds(), src, b, draw.Src, nil)
		f, err = os.Create(filepath.Join(out, asset.name))
		if err != nil {
			panic(err)
		}
		err = (&png.Encoder{CompressionLevel: png.BestCompression}).Encode(f, dst)
		closeErr := f.Close()
		if err != nil {
			panic(err)
		}
		if closeErr != nil {
			panic(closeErr)
		}
	}
}
