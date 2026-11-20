package com.campustrade.infra;

import com.campustrade.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final AppProperties props;
    private final ObjectProvider<JavaMailSender> mailSender;

    public void sendCode(String email, String code, int ttlMinutes) {
        String text = "你的验证码是 " + code + "，" + ttlMinutes + " 分钟内有效。如非本人操作请忽略。";
        if (!props.mail().enabled()) {
            log.info("[MAIL_DISABLED] 验证码 email={} code={}", email, code);
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.mail().from());
        msg.setTo(email);
        msg.setSubject("【校园二手】邮箱验证码");
        msg.setText(text);
        mailSender.getObject().send(msg);
    }
}
