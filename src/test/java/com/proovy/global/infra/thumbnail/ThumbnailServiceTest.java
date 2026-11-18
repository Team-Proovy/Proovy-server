package com.proovy.global.infra.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.gcs.GcsService;
import com.proovy.global.response.ErrorCode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class ThumbnailServiceTest {

    @Test
    void createPdfThumbnailCapsRasterScaleOnHugePdfPageGeometry() throws Exception {
        ThumbnailService thumbnailService = new ThumbnailService(
                mock(GcsService.class),
                mock(AssetRepository.class),
                mock(ApplicationContext.class));

        // 14,400 x 14,400 pt (200 x 200 inches) would otherwise allocate 30,000x30,000 px (~3.6 GB) at 150 DPI
        byte[] hugePdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(14_400f, 14_400f));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.addRect(100f, 100f, 5000f, 5000f);
                cs.fill();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            hugePdf = out.toByteArray();
        }

        byte[] jpegThumb = thumbnailService.createPdfThumbnail(hugePdf);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpegThumb));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isBetween(1, 400);
        assertThat(decoded.getHeight()).isBetween(1, 400);
    }

    @Test
    void createPdfThumbnailRejectsOversizedEmbeddedImageAndSmaskBeforeRendering() throws Exception {
        ThumbnailService thumbnailService = new ThumbnailService(
                mock(GcsService.class),
                mock(AssetRepository.class),
                mock(ApplicationContext.class));

        for (boolean viaSmask : new boolean[] {false, true}) {
            byte[] pdf;
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage();
                doc.addPage(page);
                PDResources resources = new PDResources();
                page.setResources(resources);

                PDImageXObject img = new PDImageXObject(doc);
                if (viaSmask) {
                    img.setWidth(16);
                    img.setHeight(16);
                    PDImageXObject smask = new PDImageXObject(doc);
                    smask.setWidth(8_000);
                    smask.setHeight(6_000);
                    img.getCOSObject().setItem(COSName.SMASK, smask.getCOSObject());
                } else {
                    img.setWidth(8_000);
                    img.setHeight(6_000);
                }
                resources.put(COSName.getPDFName("Im0"), img);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                pdf = out.toByteArray();
            }

            assertThatThrownBy(() -> thumbnailService.createPdfThumbnail(pdf))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET4002);
        }
    }

    @Test
    void createPdfThumbnailRejectsOversizedAndFlateBombInlineImagesBeforeRender() throws Exception {
        ThumbnailService thumbnailService = new ThumbnailService(
                mock(GcsService.class),
                mock(AssetRepository.class),
                mock(ApplicationContext.class));

        byte[] oversizedInline = createPdfWithRawContent(
                ("q\nBI\n/W 8000 /H 6000 /CS /G /BPC 8\nID\n\u0000\u0000\u0000\u0000\nEI\nQ\n")
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> thumbnailService.createPdfThumbnail(oversizedInline))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4002);

        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (var dos = new java.util.zip.DeflaterOutputStream(deflated)) {
            dos.write(new byte[65_536]);
        }
        ByteArrayOutputStream bombContent = new ByteArrayOutputStream();
        bombContent.write("q\nBI\n/W 4 /H 4 /CS /G /BPC 8 /F /Fl\nID\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        bombContent.write(deflated.toByteArray());
        bombContent.write("\nEI\nQ\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        byte[] flateBombInline = createPdfWithRawContent(bombContent.toByteArray());

        assertThatThrownBy(() -> thumbnailService.createPdfThumbnail(flateBombInline))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4002);
    }

    private static byte[] createPdfWithRawContent(byte[] contentBytes) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            page.setContents(new org.apache.pdfbox.pdmodel.common.PDStream(
                    doc, new ByteArrayInputStream(contentBytes)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void generateThumbnailSyncWithVerifiedBytesDoesNotReReadGcs() throws Exception {
        GcsService gcsService = mock(GcsService.class);
        ThumbnailService thumbnailService = new ThumbnailService(
                gcsService,
                mock(AssetRepository.class),
                mock(ApplicationContext.class));

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        byte[] verifiedBytes = out.toByteArray();

        String thumbKey = thumbnailService.generateThumbnailSync(
                verifiedBytes, "users/1/notes/10/confirmed/uuid-abcd1234_board.png", "image/png");

        assertThat(thumbKey).isEqualTo("users/1/notes/10/thumbnails/uuid-abcd1234_thumb.jpg");
        verify(gcsService, never()).readFileBytes(anyString());
        verify(gcsService, never()).readFileBytesBounded(anyString(), anyLong(), any());
        verify(gcsService, never()).readFileBytesBoundedPinned(anyString(), anyLong(), any());
        verify(gcsService).uploadFile(eq(thumbKey), any(), anyLong(), eq("image/jpeg"));
    }

    @Test
    void createImageThumbnailPreflightsDimensionsBeforeAllocatingBitmap() {
        ThumbnailService thumbnailService = new ThumbnailService(
                mock(GcsService.class),
                mock(AssetRepository.class),
                mock(ApplicationContext.class));

        // Minimal 1x1 PNG with IHDR rewritten to 20,000 x 3,000 (60MP) + valid IHDR CRC
        byte[] hugeHeaderPng = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
            0x00, 0x00, 0x4E, 0x20, // width = 20000
            0x00, 0x00, 0x0B, (byte) 0xB8, // height = 3000
            0x08, 0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00 // CRC placeholder (ImageReader reads width/height before IDAT decode)
        };
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(hugeHeaderPng, 12, 17);
        long c = crc.getValue();
        hugeHeaderPng[29] = (byte) ((c >> 24) & 0xFF);
        hugeHeaderPng[30] = (byte) ((c >> 16) & 0xFF);
        hugeHeaderPng[31] = (byte) ((c >> 8) & 0xFF);
        hugeHeaderPng[32] = (byte) (c & 0xFF);

        assertThatThrownBy(() -> thumbnailService.createImageThumbnail(hugeHeaderPng))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4002);
    }
}
