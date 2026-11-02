package com.macstudio.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.macstudio.backend.auth.service.VerificationMailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

class VerificationMailSenderTests {
    /** 실제 SMTP 대신 MIME 원문을 검사해 제목·텍스트·HTML 인증 안내가 함께 만들어지는지 확인한다. */
    @Test
    void createsMultipartVerificationEmail() throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(provider.getIfAvailable()).thenReturn(javaMailSender);
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        var sender = new VerificationMailSender(provider, "noreply@example.com");

        sender.send("member@example.com", "694623", 300);

        verify(javaMailSender).send(message);
        assertThat(message.getSubject()).isEqualTo("🎉 MAC Studio에 오신 걸 환영해요!");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("member@example.com");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        message.writeTo(raw);
        String source = raw.toString(StandardCharsets.UTF_8);
        assertThat(source).contains("multipart/mixed", "multipart/alternative", "text/plain", "text/html", "694623");
    }
}
