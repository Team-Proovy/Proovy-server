package com.proovy.infrastructure.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "spring.kakao.auth")
public class KakaoProperties {

    private String client;
    private String redirect;

    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }

    public String getRedirect() { return redirect; }
    public void setRedirect(String redirect) { this.redirect = redirect; }
}
