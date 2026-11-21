package com.proovy.domain.asset.service;

import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import com.proovy.global.util.HashUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.springframework.stereotype.Component;

/**
 * 업로드 확정 시 원본 바이트의 실제 형식(매직 바이트·파싱)·크기·PDF 페이지 수·이미지 픽셀 크기와 SHA-256을 검증한다.
 */
@Component
public class AssetContentValidator {

    public static final long PDF_MAX_BYTES = 20L * 1024 * 1024;
    public static final int PDF_MAX_PAGES = 100;
    public static final long IMAGE_MAX_BYTES = 15L * 1024 * 1024;
    public static final int IMAGE_MAX_DIMENSION_PX = 16_384;
    public static final long IMAGE_MAX_PIXELS = 40_000_000L;
    public static final long MAX_PAGE_EMBEDDED_IMAGE_PIXELS = 80_000_000L;
    public static final int MAX_PDF_INSPECTED_XOBJECTS = 2_048;
    public static final long MAX_PDF_CONTENT_STREAM_BYTES = 8L * 1024 * 1024;
    public static final int MAX_PDF_CONTENT_STREAM_TOKENS = 200_000;
    public static final long MAX_INLINE_IMAGE_DECODED_BYTES = 16L * 1024 * 1024;
    public static final long MAX_PAGE_INLINE_IMAGE_DECODED_BYTES = 32L * 1024 * 1024;

    public record ValidatedAssetMetadata(
            String sha256,
            long byteSize,
            Integer pageCount,
            Integer pixelWidth,
            Integer pixelHeight,
            Long pixelCount) {
    }

    public ValidatedAssetMetadata validate(byte[] bytes, String declaredMimeType) {
        if (bytes == null || bytes.length == 0 || declaredMimeType == null || declaredMimeType.isBlank()) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }

        String sha256 = HashUtils.sha256(bytes);
        if ("application/pdf".equals(declaredMimeType)) {
            return validatePdf(bytes, sha256);
        }
        return switch (declaredMimeType) {
            case "image/png", "image/jpeg", "image/webp" -> validateImage(bytes, declaredMimeType, sha256);
            default -> throw new BusinessException(ErrorCode.ASSET4001);
        };
    }

    private ValidatedAssetMetadata validatePdf(byte[] bytes, String sha256) {
        if (bytes.length > PDF_MAX_BYTES) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        if (!hasPdfSignature(bytes)) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            int pages = document.getNumberOfPages();
            if (pages < 1) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            if (pages > PDF_MAX_PAGES) {
                throw new BusinessException(ErrorCode.ASSET4002);
            }
            for (int i = 0; i < pages; i++) {
                PDPage page = document.getPage(i);
                PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                if (box == null
                        || !Float.isFinite(box.getWidth())
                        || !Float.isFinite(box.getHeight())
                        || box.getWidth() <= 0f
                        || box.getHeight() <= 0f) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                validatePdfPageEmbeddedImages(page);
            }
            return new ValidatedAssetMetadata(sha256, bytes.length, pages, null, null, null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
    }

    public static void validatePdfPageEmbeddedImages(PDPage page) {
        if (page == null) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        // [0]: cumulative pixels, [1]: inspected objects/inline images, [2]: cumulative decoded inline bytes
        long[] stats = new long[3];
        try {
            inspectPdfContentStream(page, visited, stats);
            inspectPdfResources(page.getResources(), visited, stats);
            for (PDAnnotation annotation : page.getAnnotations()) {
                PDAppearanceDictionary appearance = annotation.getAppearance();
                if (appearance == null) {
                    continue;
                }
                inspectPdfAppearanceEntry(appearance.getNormalAppearance(), visited, stats);
                inspectPdfAppearanceEntry(appearance.getRolloverAppearance(), visited, stats);
                inspectPdfAppearanceEntry(appearance.getDownAppearance(), visited, stats);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
    }

    private static void inspectPdfAppearanceEntry(
            PDAppearanceEntry entry, Set<COSBase> visited, long[] stats) throws IOException {
        if (entry == null) {
            return;
        }
        if (entry.isStream()) {
            PDAppearanceStream stream = entry.getAppearanceStream();
            if (stream != null) {
                inspectPdfFormXObject(stream, visited, stats);
            }
        } else if (entry.isSubDictionary()) {
            for (PDAppearanceStream stream : entry.getSubDictionary().values()) {
                if (stream != null) {
                    inspectPdfFormXObject(stream, visited, stats);
                }
            }
        }
    }

    private static void inspectPdfFormXObject(
            PDFormXObject formXObject, Set<COSBase> visited, long[] stats) throws IOException {
        if (formXObject == null) {
            return;
        }
        COSBase cos = formXObject.getCOSObject();
        if (cos != null && !visited.add(cos)) {
            return;
        }
        inspectPdfContentStream(formXObject, visited, stats);
        inspectPdfResources(formXObject.getResources(), visited, stats);
    }

    private static void inspectPdfResources(PDResources resources, Set<COSBase> visited, long[] stats)
            throws IOException {
        if (resources == null) {
            return;
        }
        COSBase cos = resources.getCOSObject();
        if (cos != null && !visited.add(cos)) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            countInspectedPdfObject(stats);
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject imageXObject) {
                inspectPdfImageXObject(imageXObject, visited, stats);
            } else if (xObject instanceof PDFormXObject formXObject) {
                inspectPdfFormXObject(formXObject, visited, stats);
            }
        }
        for (COSName patternName : resources.getPatternNames()) {
            countInspectedPdfObject(stats);
            PDAbstractPattern pattern = resources.getPattern(patternName);
            if (pattern instanceof PDTilingPattern tilingPattern) {
                COSBase patternCos = tilingPattern.getCOSObject();
                if (patternCos == null || visited.add(patternCos)) {
                    inspectPdfContentStream(tilingPattern, visited, stats);
                    inspectPdfResources(tilingPattern.getResources(), visited, stats);
                }
            }
        }
        for (COSName gsName : resources.getExtGStateNames()) {
            countInspectedPdfObject(stats);
            PDExtendedGraphicsState gs = resources.getExtGState(gsName);
            PDSoftMask softMask = gs != null ? gs.getSoftMask() : null;
            if (softMask != null && softMask.getGroup() != null) {
                inspectPdfFormXObject(softMask.getGroup(), visited, stats);
            }
        }
    }

    private static void inspectPdfContentStream(
            PDContentStream contentStream, Set<COSBase> visited, long[] stats) throws IOException {
        if (contentStream == null) {
            return;
        }
        byte[] contentBytes;
        try (InputStream raw = contentStream.getContents()) {
            if (raw == null) {
                return;
            }
            contentBytes = readBounded(raw, MAX_PDF_CONTENT_STREAM_BYTES);
        }
        if (contentBytes.length == 0) {
            return;
        }
        PDFStreamParser parser = new PDFStreamParser(contentBytes);
        int tokens = 0;
        Object token;
        while ((token = parser.parseNextToken()) != null) {
            tokens++;
            if (tokens > MAX_PDF_CONTENT_STREAM_TOKENS) {
                throw new BusinessException(ErrorCode.ASSET4002);
            }
            if (token instanceof Operator op && "BI".equals(op.getName())) {
                countInspectedPdfObject(stats);
                inspectInlineImage(op.getImageParameters(), op.getImageData(), stats);
            }
        }
    }

    private static void inspectInlineImage(
            COSDictionary params, byte[] rawData, long[] stats) throws IOException {
        if (params == null || rawData == null) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        int width = params.getInt(COSName.W, COSName.WIDTH, -1);
        int height = params.getInt(COSName.H, COSName.HEIGHT, -1);
        if (width < 1 || height < 1) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        if (width > IMAGE_MAX_DIMENSION_PX || height > IMAGE_MAX_DIMENSION_PX) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        long pixels = (long) width * height;
        if (pixels > IMAGE_MAX_PIXELS) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        stats[0] += pixels;
        if (stats[0] > MAX_PAGE_EMBEDDED_IMAGE_PIXELS) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }

        long maxInlineBytes = Math.min(
                MAX_INLINE_IMAGE_DECODED_BYTES,
                Math.max(4096L, pixels * 8L + 4096L));
        List<String> filters = extractInlineFilterNames(params);
        byte[] current = rawData;
        for (int i = 0; i < filters.size(); i++) {
            String filterName = normalizeInlineFilterName(filters.get(i));
            if ("DCTDecode".equals(filterName) || "JPXDecode".equals(filterName)) {
                if (looksLikeJpeg(current)) {
                    inspectEmbeddedJpegDimensions(current);
                }
            }
            Filter filter = FilterFactory.INSTANCE.getFilter(filterName);
            BoundedByteArrayOutputStream boundedOut = new BoundedByteArrayOutputStream(maxInlineBytes);
            filter.decode(new ByteArrayInputStream(current), boundedOut, params, i);
            current = boundedOut.toByteArray();
        }
        if (current.length > maxInlineBytes) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        stats[2] += current.length;
        if (stats[2] > MAX_PAGE_INLINE_IMAGE_DECODED_BYTES) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        if (looksLikeJpeg(current)) {
            inspectEmbeddedJpegDimensions(current);
        }
    }

    private static List<String> extractInlineFilterNames(COSDictionary params) {
        COSBase filterObj = params.getDictionaryObject(COSName.F, COSName.FILTER);
        if (filterObj instanceof COSName name) {
            return List.of(name.getName());
        }
        if (filterObj instanceof COSArray array) {
            List<String> names = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                COSBase item = array.getObject(i);
                if (item instanceof COSName n) {
                    names.add(n.getName());
                }
            }
            return names;
        }
        return List.of();
    }

    private static String normalizeInlineFilterName(String name) {
        return switch (name) {
            case "AHx" -> COSName.ASCII_HEX_DECODE.getName();
            case "A85" -> COSName.ASCII85_DECODE.getName();
            case "LZW" -> COSName.LZW_DECODE.getName();
            case "Fl" -> COSName.FLATE_DECODE.getName();
            case "RL" -> COSName.RUN_LENGTH_DECODE.getName();
            case "CCF" -> COSName.CCITTFAX_DECODE.getName();
            case "DCT" -> COSName.DCT_DECODE.getName();
            default -> name;
        };
    }

    private static boolean looksLikeJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private static void inspectEmbeddedJpegDimensions(byte[] jpegBytes) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(jpegBytes))) {
            if (iis == null) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 1 || h < 1) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                if (w > IMAGE_MAX_DIMENSION_PX || h > IMAGE_MAX_DIMENSION_PX || (long) w * h > IMAGE_MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.ASSET4002);
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        BoundedByteArrayOutputStream out = new BoundedByteArrayOutputStream(maxBytes);
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final long maxBytes;

        BoundedByteArrayOutputStream(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) {
            if (count + 1L > maxBytes) {
                throw new BusinessException(ErrorCode.ASSET4002);
            }
            super.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (len < 0) {
                throw new IndexOutOfBoundsException();
            }
            if ((long) count + len > maxBytes) {
                throw new BusinessException(ErrorCode.ASSET4002);
            }
            super.write(b, off, len);
        }
    }

    private static void inspectPdfImageXObject(
            PDImageXObject image, Set<COSBase> visited, long[] stats) throws IOException {
        if (image == null) {
            return;
        }
        COSBase cos = image.getCOSObject();
        if (cos != null && !visited.add(cos)) {
            return;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 1 || height < 1) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        if (width > IMAGE_MAX_DIMENSION_PX || height > IMAGE_MAX_DIMENSION_PX) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        long pixels = (long) width * height;
        if (pixels > IMAGE_MAX_PIXELS) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        stats[0] += pixels;
        if (stats[0] > MAX_PAGE_EMBEDDED_IMAGE_PIXELS) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        PDImageXObject softMask = image.getSoftMask();
        if (softMask != null) {
            countInspectedPdfObject(stats);
            inspectPdfImageXObject(softMask, visited, stats);
        }
        PDImageXObject mask = image.getMask();
        if (mask != null) {
            countInspectedPdfObject(stats);
            inspectPdfImageXObject(mask, visited, stats);
        }
    }

    private static void countInspectedPdfObject(long[] stats) {
        stats[1]++;
        if (stats[1] > MAX_PDF_INSPECTED_XOBJECTS) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
    }

    private ValidatedAssetMetadata validateImage(byte[] bytes, String mimeType, String sha256) {
        if (bytes.length > IMAGE_MAX_BYTES) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        if (!matchesImageSignature(bytes, mimeType)) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        Dimensions dims;
        if ("image/webp".equals(mimeType)) {
            dims = parseWebpDimensions(bytes);
            validateImageDimensionBounds(dims);
            parseAndDecodeImageIo(bytes, mimeType, dims);
        } else {
            dims = parseAndDecodeImageIo(bytes, mimeType, null);
            validateImageDimensionBounds(dims);
        }
        long pixelCount = (long) dims.width() * dims.height();
        return new ValidatedAssetMetadata(
                sha256, bytes.length, null, dims.width(), dims.height(), pixelCount);
    }

    private static void validateImageDimensionBounds(Dimensions dims) {
        if (dims.width() < 1 || dims.height() < 1) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        if (dims.width() > IMAGE_MAX_DIMENSION_PX || dims.height() > IMAGE_MAX_DIMENSION_PX) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
        long pixelCount = (long) dims.width() * dims.height();
        if (pixelCount > IMAGE_MAX_PIXELS) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
    }

    private Dimensions parseAndDecodeImageIo(byte[] bytes, String mimeType, Dimensions expectedDims) {
        String expectedFormat = switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpeg";
            case "image/webp" -> "webp";
            default -> throw new BusinessException(ErrorCode.ASSET4001);
        };
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            ImageReader reader = readers.next();
            try {
                if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                if (expectedDims != null && (width != expectedDims.width() || height != expectedDims.height())) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                if (width > IMAGE_MAX_DIMENSION_PX || height > IMAGE_MAX_DIMENSION_PX
                        || (long) width * height > IMAGE_MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.ASSET4002);
                }
                if (reader.read(0) == null) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
    }

    private static Dimensions parseWebpDimensions(byte[] bytes) {
        if (bytes.length < 30) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        long riffSize = uint32Le(bytes, 4);
        if (riffSize < 20 || riffSize + 8L > bytes.length || bytes.length - (riffSize + 8L) > 1L) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        int riffEnd = (int) (riffSize + 8L);
        int offset = 12;
        if (offset + 8 > riffEnd) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        String firstChunkId = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
        long firstChunkSize = uint32Le(bytes, offset + 4);
        long firstDataStartLong = offset + 8L;
        long firstDataEndLong = firstDataStartLong + firstChunkSize;
        if (firstDataEndLong > riffEnd) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        int firstDataStart = (int) firstDataStartLong;
        int firstChunkLen = (int) firstChunkSize;
        int nextOffset = (int) Math.min(firstDataEndLong + (firstChunkSize & 1L), riffEnd);

        return switch (firstChunkId) {
            case "VP8 " -> {
                Dimensions dims = parseVp8Dimensions(bytes, firstDataStart, firstChunkLen);
                validateTrailingWebpChunks(bytes, nextOffset, riffEnd);
                yield dims;
            }
            case "VP8L" -> {
                Dimensions dims = parseVp8lDimensions(bytes, firstDataStart, firstChunkLen);
                validateTrailingWebpChunks(bytes, nextOffset, riffEnd);
                yield dims;
            }
            case "VP8X" -> {
                if (firstChunkLen != 10) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                int flags = bytes[firstDataStart] & 0xFF;
                if ((flags & 0x02) != 0) {
                    // Animated WebP is unsupported; reject explicitly at validation time
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                int canvasWidth = uint24Le(bytes, firstDataStart + 4) + 1;
                int canvasHeight = uint24Le(bytes, firstDataStart + 7) + 1;
                boolean foundImageChunk = false;
                int pos = nextOffset;
                while (pos < riffEnd) {
                    if (pos + 8 > riffEnd) {
                        throw new BusinessException(ErrorCode.ASSET4001);
                    }
                    String cid = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
                    long cSize = uint32Le(bytes, pos + 4);
                    long cDataStartLong = pos + 8L;
                    long cDataEndLong = cDataStartLong + cSize;
                    if (cDataEndLong > riffEnd) {
                        throw new BusinessException(ErrorCode.ASSET4001);
                    }
                    if ("ANIM".equals(cid) || "ANMF".equals(cid)) {
                        throw new BusinessException(ErrorCode.ASSET4001);
                    }
                    int cDataStart = (int) cDataStartLong;
                    int cLen = (int) cSize;
                    if ("VP8 ".equals(cid) || "VP8L".equals(cid)) {
                        Dimensions frameDims = "VP8 ".equals(cid)
                                ? parseVp8Dimensions(bytes, cDataStart, cLen)
                                : parseVp8lDimensions(bytes, cDataStart, cLen);
                        if (frameDims.width() != canvasWidth || frameDims.height() != canvasHeight) {
                            throw new BusinessException(ErrorCode.ASSET4001);
                        }
                        foundImageChunk = true;
                    }
                    pos = (int) Math.min(cDataEndLong + (cSize & 1L), riffEnd);
                }
                if (!foundImageChunk) {
                    throw new BusinessException(ErrorCode.ASSET4001);
                }
                yield new Dimensions(canvasWidth, canvasHeight);
            }
            default -> throw new BusinessException(ErrorCode.ASSET4001);
        };
    }

    private static void validateTrailingWebpChunks(byte[] bytes, int startOffset, int riffEnd) {
        int pos = startOffset;
        while (pos < riffEnd) {
            if (pos + 8 > riffEnd) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            String cid = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            if ("ANIM".equals(cid) || "ANMF".equals(cid)) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            long cSize = uint32Le(bytes, pos + 4);
            long cDataEndLong = pos + 8L + cSize;
            if (cDataEndLong > riffEnd) {
                throw new BusinessException(ErrorCode.ASSET4001);
            }
            pos = (int) Math.min(cDataEndLong + (cSize & 1L), riffEnd);
        }
    }

    private static Dimensions parseVp8Dimensions(byte[] bytes, int dataStart, int chunkLen) {
        if (chunkLen < 10) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        if ((bytes[dataStart] & 0x01) != 0
                || (bytes[dataStart + 3] & 0xFF) != 0x9D
                || (bytes[dataStart + 4] & 0xFF) != 0x01
                || (bytes[dataStart + 5] & 0xFF) != 0x2A) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        int width = uint16Le(bytes, dataStart + 6) & 0x3FFF;
        int height = uint16Le(bytes, dataStart + 8) & 0x3FFF;
        if (width < 1 || height < 1) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        return new Dimensions(width, height);
    }

    private static Dimensions parseVp8lDimensions(byte[] bytes, int dataStart, int chunkLen) {
        if (chunkLen < 5 || (bytes[dataStart] & 0xFF) != 0x2F) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        long bits = uint32Le(bytes, dataStart + 1);
        int version = (int) ((bits >> 29) & 0x07);
        if (version != 0) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
        int width = (int) (bits & 0x3FFF) + 1;
        int height = (int) ((bits >> 14) & 0x3FFF) + 1;
        return new Dimensions(width, height);
    }

    private static boolean hasPdfSignature(byte[] bytes) {
        if (bytes.length < 5) {
            return false;
        }
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    private static boolean matchesImageSignature(byte[] bytes, String mimeType) {
        return switch (mimeType) {
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                    && bytes[4] == '\r' && bytes[5] == '\n' && (bytes[6] & 0xFF) == 0x1A && bytes[7] == '\n';
            case "image/jpeg" -> bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
            case "image/webp" -> bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private static int uint16Le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int uint24Le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
    }

    private static long uint32Le(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private record Dimensions(int width, int height) {
    }
}
