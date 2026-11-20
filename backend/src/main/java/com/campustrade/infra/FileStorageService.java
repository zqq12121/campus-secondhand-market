package com.campustrade.infra;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/** 本地磁盘存储（V1.0），按文件头校验真实类型，仅 jpg/png/webp */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Set<String> BIZ = Set.of("GOODS", "AVATAR", "CHAT", "EVIDENCE");
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    private final AppProperties props;

    public String store(MultipartFile file, String biz) {
        if (biz == null || !BIZ.contains(biz)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "biz 取值不合法");
        }
        if (file.isEmpty() || file.getSize() > MAX_SIZE) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            String ext = detect(head);
            if (ext == null) {
                throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
            }
            String rel = biz.toLowerCase() + "/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                    + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path target = Path.of(props.upload().dir()).resolve(rel).toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            file.transferTo(target);
            return props.upload().publicBaseUrl() + "/" + rel;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 是否为本平台上传的 URL */
    public boolean isOwnUrl(String url) {
        return url != null && url.startsWith(props.upload().publicBaseUrl() + "/");
    }

    private static String detect(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "png";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
