package com.macstudio.backend.auth.service;

import com.macstudio.backend.global.exception.ApiException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 최초 발송처럼 잠글 행이 없는 경우도 MySQL 이름 잠금으로 동일 이메일 요청을 직렬화한다. */
@Component
public class EmailRequestLock {
    private final DataSource dataSource;
    private final AuthCrypto crypto;
    /** 잠금 전용 연결과 이메일을 노출하지 않는 잠금 이름 생성기를 주입받는다. */
    public EmailRequestLock(DataSource dataSource, AuthCrypto crypto) { this.dataSource = dataSource; this.crypto = crypto; }
    /** 대기 없이 잠금을 획득하고 작업 후 같은 연결에서 반드시 해제한다. SMTP 중에는 행 잠금을 유지하지 않는다. */
    public <T> T execute(String email, Supplier<T> work) {
        String name = "mac:email:" + HexFormat.of().formatHex(crypto.hash(email)).substring(0, 48);
        try (var connection = dataSource.getConnection()) {
            try (var query = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                query.setString(1, name);
                try (var result = query.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                            "AUTH_REQUEST_BUSY", "같은 이메일의 요청이 처리 중입니다.");
                }
            }
            try { return work.get(); }
            finally {
                try (var query = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    query.setString(1, name);
                    try (var result = query.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1) throw new SQLException("Lock release failed");
                    }
                } catch (SQLException releaseFailure) {
                    // 이름 잠금을 가진 연결이 풀로 돌아가지 않도록 물리 연결을 종료한다.
                    connection.abort(Runnable::run);
                    throw releaseFailure;
                }
            }
        } catch (SQLException e) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_STORAGE_UNAVAILABLE", "인증 저장소를 사용할 수 없습니다."); }
    }
}
