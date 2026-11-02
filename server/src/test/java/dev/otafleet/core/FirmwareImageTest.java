package dev.otafleet.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.otafleet.core.FirmwareImage.AppDescriptor;
import dev.otafleet.core.FirmwareImage.InvalidImageException;
import org.junit.jupiter.api.Test;

class FirmwareImageTest {

    @Test
    void readsVersionAndProjectName() {
        AppDescriptor desc = FirmwareImage.readDescriptor(TestImages.build("1.4.2", "meu-projeto", 2048));
        assertThat(desc.version()).isEqualTo("1.4.2");
        assertThat(desc.projectName()).isEqualTo("meu-projeto");
    }

    @Test
    void rejectsTooSmallFile() {
        assertThatThrownBy(() -> FirmwareImage.readDescriptor(new byte[100]))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("pequeno");
        assertThatThrownBy(() -> FirmwareImage.readDescriptor(null))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void rejectsWrongImageMagic() {
        byte[] image = TestImages.build("1.0.0");
        image[0] = 0x7F;
        assertThatThrownBy(() -> FirmwareImage.readDescriptor(image))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("0xE9");
    }

    @Test
    void rejectsMissingAppDescriptor() {
        byte[] image = TestImages.build("1.0.0");
        image[32] = 0;
        assertThatThrownBy(() -> FirmwareImage.readDescriptor(image))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("magic_word");
    }

    @Test
    void rejectsVersionWithoutTerminator() {
        byte[] image = TestImages.build("1.0.0");
        for (int i = 48; i < 80; i++) {
            image[i] = 'x';
        }
        assertThatThrownBy(() -> FirmwareImage.readDescriptor(image))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsEmptyVersion() {
        byte[] image = TestImages.build("1.0.0");
        image[48] = 0;
        assertThatThrownBy(() -> FirmwareImage.readDescriptor(image))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("vazia");
    }

    @Test
    void acceptsVersionUsingAllButTerminator() {
        String longVersion = "1".repeat(31);
        AppDescriptor desc = FirmwareImage.readDescriptor(TestImages.build(longVersion));
        assertThat(desc.version()).isEqualTo(longVersion);
    }
}
