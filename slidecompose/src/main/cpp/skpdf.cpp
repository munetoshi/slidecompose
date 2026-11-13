// skpdf: writes a PDF with Skia's PDF backend, which Skiko leaves out, for SlideCompose's PDF export.
// It is built against the same Skia as Skiko, so that it reads the pictures Skiko records.
//
//   skpdf [--title <title>] [--raster-dpi <dpi>] <out.pdf>   reads the pages from standard input
//   skpdf --version                                          prints the Skia version it was built with
//
// Each page on standard input is a type byte, 'P' for a Skia picture (SKP) or 'I' for an encoded image
// filling the page, the page's width and height in points as 32-bit floats, the length of the data as a
// 32-bit int, and the data, all big-endian. The type byte 'E' ends the document.

#include "include/codec/SkCodec.h"
#include "include/codec/SkJpegDecoder.h"
#include "include/codec/SkPngDecoder.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkData.h"
#include "include/core/SkDocument.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkImage.h"
#include "include/core/SkPicture.h"
#include "include/core/SkSerialProcs.h"
#include "include/core/SkStream.h"
#include "include/core/SkTypeface.h"
#include "include/docs/SkPDFDocument.h"
#include "include/docs/SkPDFJpegHelpers.h"
#include "include/ports/SkFontMgr_mac_ct.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>

#ifndef SKPDF_SKIA_VERSION
#define SKPDF_SKIA_VERSION "unknown"
#endif

namespace {

[[noreturn]] void fail(const char* message) {
    fprintf(stderr, "skpdf: %s\n", message);
    exit(1);
}

void readFully(void* buffer, size_t length) {
    if (length > 0 && fread(buffer, 1, length, stdin) != length) fail("unexpected end of input");
}

uint32_t readU32() {
    uint8_t b[4];
    readFully(b, 4);
    return (uint32_t(b[0]) << 24) | (uint32_t(b[1]) << 16) | (uint32_t(b[2]) << 8) | uint32_t(b[3]);
}

float readFloat() {
    uint32_t bits = readU32();
    float value;
    memcpy(&value, &bits, sizeof value);
    return value;
}

// Each picture carries its own copy of every typeface it uses. Handing SkPDF the same typeface for the
// same font on every page lets the pages share the fonts embedded in the PDF.
struct Typefaces {
    sk_sp<SkFontMgr> fonts = SkFontMgr_New_CoreText(nullptr);
    std::unordered_map<std::string, sk_sp<SkTypeface>> seen;
};

uint64_t hashOf(const uint8_t* bytes, size_t length) {
    uint64_t hash = 14695981039346656037ull;
    for (size_t i = 0; i < length; i++) hash = (hash ^ bytes[i]) * 1099511628211ull;
    return hash;
}

sk_sp<SkTypeface> deserializeTypeface(SkStream& stream, void* context) {
    auto* typefaces = static_cast<Typefaces*>(context);
    const auto* base = static_cast<const uint8_t*>(stream.getMemoryBase());
    size_t start = stream.getPosition();
    sk_sp<SkTypeface> typeface = SkTypeface::MakeDeserialize(&stream, typefaces->fonts);
    if (!typeface || !base) return typeface;
    size_t length = stream.getPosition() - start;
    std::string key = std::to_string(length) + ":" + std::to_string(hashOf(base + start, length));
    auto [entry, added] = typefaces->seen.emplace(key, typeface);
    return entry->second;
}

}  // namespace

int main(int argc, char** argv) {
    SkPDF::Metadata metadata = SkPDF::JPEG::MetadataWithCallbacks();
    metadata.fCreator = "SlideCompose";
    const char* out = nullptr;
    for (int i = 1; i < argc; i++) {
        std::string arg = argv[i];
        if (arg == "--version") {
            printf("%s\n", SKPDF_SKIA_VERSION);
            return 0;
        } else if (arg == "--title" && i + 1 < argc) {
            metadata.fTitle = argv[++i];
        } else if (arg == "--raster-dpi" && i + 1 < argc) {
            metadata.fRasterDPI = (SkScalar) atof(argv[++i]);
        } else if (!out && arg[0] != '-') {
            out = argv[i];
        } else {
            fail("usage: skpdf [--title <title>] [--raster-dpi <dpi>] <out.pdf> < pages");
        }
    }
    if (!out) fail("no output file");

    SkCodecs::Register(SkPngDecoder::Decoder());
    SkCodecs::Register(SkJpegDecoder::Decoder());
    Typefaces typefaces;
    SkDeserialProcs procs;
    procs.fTypefaceStreamProc = deserializeTypeface;
    procs.fTypefaceCtx = &typefaces;

    SkFILEWStream file(out);
    if (!file.isValid()) fail("cannot write the output file");
    sk_sp<SkDocument> document = SkPDF::MakeDocument(&file, metadata);
    if (!document) fail("cannot make a PDF");
    for (;;) {
        int type = fgetc(stdin);
        if (type == 'E') break;
        if (type != 'P' && type != 'I') fail("bad page type");
        float width = readFloat();
        float height = readFloat();
        uint32_t length = readU32();
        sk_sp<SkData> data = SkData::MakeUninitialized(length);
        readFully(data->writable_data(), length);
        SkCanvas* canvas = document->beginPage(width, height);
        if (type == 'P') {
            sk_sp<SkPicture> picture = SkPicture::MakeFromData(data.get(), &procs);
            if (!picture) fail("cannot read a picture");
            canvas->drawPicture(picture);
        } else {
            sk_sp<SkImage> image = SkImages::DeferredFromEncodedData(data);
            if (!image) fail("cannot read an image");
            canvas->drawImageRect(image, SkRect::MakeWH(width, height), SkSamplingOptions(SkFilterMode::kLinear));
        }
        document->endPage();
    }
    document->close();
    return 0;
}
