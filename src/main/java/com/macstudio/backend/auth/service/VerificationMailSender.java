package com.macstudio.backend.auth.service;

import com.macstudio.backend.global.exception.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;

/** SMTP 전송만 담당하며 DB 트랜잭션이나 인증 상태를 변경하지 않는다. */
@Component
public class VerificationMailSender {
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    /** SMTP Bean과 발신 주소를 받는다. 미설정 상태에서는 실제 발송을 거절한다. */
    public VerificationMailSender(ObjectProvider<JavaMailSender> sender, @Value("${app.auth.mail-from:}") String from) {
        this.sender = sender; this.from = from;
    }
    /** SMTP 요청을 제출하고 실패 시 내부 서버 정보 대신 공통 오류만 전달한다. */
    public void send(String email, String code, long expiresIn) {
        JavaMailSender mailSender = sender.getIfAvailable();
        if (mailSender == null || from.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "MAIL_NOT_CONFIGURED", "메일 발송 설정이 필요합니다.");
        MimeMessage message = mailSender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("🎉 MAC Studio에 오신 걸 환영해요!");
            helper.setText(plainText(code, expiresIn), htmlText(code, expiresIn));
            mailSender.send(message);
        }
        catch (MessagingException | MailException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_SEND_FAILED",
                    "메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** HTML을 표시하지 않는 메일 클라이언트를 위한 동일 내용의 안전한 대체 본문. */
    private String plainText(String code, long expiresIn) {
        return """
                🎉 MAC Studio에 오신 걸 환영해요!

                반가워요! 👋
                데이터를 멋진 차트로 바꾸는 여정을 함께 시작해 볼까요?
                회원가입을 마치려면 아래 인증코드를 입력해 주세요.

                ✨ 인증코드: %s
                ⏰ 유효시간: %s

                🔐 인증코드는 한 번만 사용할 수 있으며 다른 사람에게 알려주면 안 됩니다.
                🛡️ 본인이 요청하지 않았다면 이 메일을 안전하게 무시해 주세요.

                곧 MAC Studio에서 만나요! 🚀
                """.formatted(code, expiryLabel(expiresIn));
    }

    /** 외부 이미지·스크립트 없이 주요 메일 클라이언트에서 읽기 쉬운 가입 인증 안내를 만든다. */
    private String htmlText(String code, long expiresIn) {
        return """
                <!doctype html>
                <html lang="ko">
                  <body style="margin:0;padding:0;background:#f4f6f8;font-family:Arial,'Apple SD Gothic Neo','Noto Sans KR',sans-serif;color:#20252b;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f8;padding:32px 16px;">
                      <tr><td align="center">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;background:#ffffff;border:1px solid #e5e9ee;border-radius:16px;overflow:hidden;">
                          <tr>
                            <td style="padding:24px 32px;background:#171b21;color:#ffffff;font-size:22px;font-weight:700;letter-spacing:.3px;">
                              ✨ MAC Studio
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:36px 32px 12px;font-size:24px;font-weight:700;line-height:1.4;">
                              🎉 MAC Studio에 오신 걸 환영해요!
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px;color:#59616b;font-size:15px;line-height:1.8;">
                              반가워요! 👋<br>
                              데이터를 멋진 차트로 바꾸는 여정을 함께 시작해 볼까요?<br>
                              회원가입을 마치려면 아래 인증코드를 화면에 입력해 주세요.
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:28px 32px 18px;">
                              <div style="padding:22px;text-align:center;background:#f1f5ff;border:1px solid #d9e3ff;border-radius:12px;">
                                <div style="margin-bottom:8px;color:#667085;font-size:13px;">✨ 이메일 인증코드</div>
                                <div style="font-size:34px;font-weight:800;letter-spacing:8px;color:#3156d3;">%s</div>
                              </div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px 30px;color:#59616b;font-size:14px;line-height:1.8;">
                              ⏰ 이 코드는 <strong>%s</strong> 동안 유효하며 한 번만 사용할 수 있습니다.<br>
                              🔐 보안을 위해 인증코드를 다른 사람에게 알려주지 마세요.<br><br>
                              인증을 마치고 MAC Studio에서 만나요! 🚀
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:20px 32px;background:#fafbfc;border-top:1px solid #edf0f2;color:#7a828c;font-size:12px;line-height:1.7;">
                              🛡️ 본인이 회원가입을 요청하지 않았다면 별도 조치 없이 이 메일을 안전하게 무시해 주세요.<br>
                              이 메일은 발신 전용입니다.
                            </td>
                          </tr>
                        </table>
                      </td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(code, expiryLabel(expiresIn));
    }

    /** 초 단위 설정을 메일에서 읽기 쉬운 분/초 문구로 표시한다. */
    private String expiryLabel(long expiresIn) {
        return expiresIn > 0 && expiresIn % 60 == 0 ? (expiresIn / 60) + "분" : expiresIn + "초";
    }
}
