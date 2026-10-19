package org.example.feedablackhole.auth.entity;

/**
 * 계정으로 들어오는 방법의 종류. DB에는 이름(문자열)으로 저장하므로 이름을 바꾸거나 값의 순서를 걱정할 필요가 없다.
 * 추후 소셜 로그인을 붙인다면 GOOGLE, APPLE 등을 여기에 추가한다.
 */
public enum AuthIdentityType {
    GUEST
}
