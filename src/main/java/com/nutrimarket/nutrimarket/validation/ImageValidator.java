package com.nutrimarket.nutrimarket.validation;

import com.nutrimarket.nutrimarket.config.AgentConfiguration.Policy;
import com.nutrimarket.nutrimarket.exception.AgentException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ImageValidator {
    private final Policy policy;
    public ImageValidator(Policy policy) { this.policy = policy; }
    public String validate(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > policy.maxImageBytes()) throw invalid("The image is empty or exceeds the size limit.");
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid("Unable to decode the image. Upload a JPEG or PNG.");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) throw invalid("Only JPEG and PNG images are supported.");
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < policy.minImageSide() || h < policy.minImageSide())
                    throw invalid("The photo is too small to analyse. Use a larger photo or describe your meal instead.");
                if ((long)w*h > policy.maxImagePixels())
                    throw invalid("The photo resolution is too large. Resize it or describe your meal instead.");
                var image = reader.read(0);
                if (image == null) throw invalid("The image is damaged. Please upload it again.");
                // Reject blank / severely underexposed / overexposed images locally.
                double sum=0, squares=0; int count=0;
                for (int y=0; y<h; y+=Math.max(1,h/128)) for(int x=0; x<w; x+=Math.max(1,w/128)) {
                    int rgb=image.getRGB(x,y);
                    double v=0.2126*((rgb>>16)&255)+0.7152*((rgb>>8)&255)+0.0722*(rgb&255);
                    sum+=v; squares+=v*v; count++;
                }
                double mean=sum/count;
                if (mean<5 || mean>250 || squares/count-mean*mean<4)
                    throw invalid("The image is too dark, overexposed or lacks recognizable content. Use a clear meal photo.");
                return "image/"+format;
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) { throw invalid("The image is damaged. Please upload it again."); }
    }
    private AgentException invalid(String message) {
        return new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE", message);
    }
}
