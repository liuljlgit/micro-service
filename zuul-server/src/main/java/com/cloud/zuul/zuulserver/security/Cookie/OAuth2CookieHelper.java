package com.cloud.zuul.zuulserver.security.Cookie;

import com.alibaba.fastjson.JSONObject;
import com.cloud.zuul.zuulserver.security.OAuth2Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.security.jwt.Jwt;
import org.springframework.security.jwt.JwtHelper;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2RefreshToken;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.token.AccessTokenConverter;
import org.springframework.util.StringUtils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Helps with OAuth2 cookie handling.
 */
public class OAuth2CookieHelper {
    /**
     * 登录用户信息
     */
    public static final String ACCESS_ADDITIONAL_INFO = "additional-info";
    /**
     * Name of the access token cookie.
     */
    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    /**
     * Name of the refresh token cookie in case of remember me.
     */
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    /**
     * Name of the session-only refresh token in case the user did not check remember me.
     */
    public static final String SESSION_TOKEN_COOKIE = "session_token";

    /**
     * The names of the Cookies we set.
     */
    private static final List<String> COOKIE_NAMES = Arrays.asList(ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE,
        SESSION_TOKEN_COOKIE, ACCESS_ADDITIONAL_INFO);

    /**
     * Number of seconds to expire refresh token cookies before the enclosed token expires.
     * This makes sure we don't run into race conditions where the cookie is still there but
     * expires while we process it.
     */
    private static final long REFRESH_TOKEN_EXPIRATION_WINDOW_SECS = 3L;

    private final Logger log = LoggerFactory.getLogger(OAuth2CookieHelper.class);

    private OAuth2Properties oAuth2Properties;

    /**
     * Used to parse JWT claims.
     */
    private JsonParser jsonParser = JsonParserFactory.getJsonParser();

    public OAuth2CookieHelper(OAuth2Properties oAuth2Properties) {
        this.oAuth2Properties = oAuth2Properties;
    }

    public static Cookie getAccessTokenCookie(HttpServletRequest request) {
        return getCookie(request, ACCESS_TOKEN_COOKIE);
    }

    public static Cookie getRefreshTokenCookie(HttpServletRequest request) {
        Cookie cookie = getCookie(request, REFRESH_TOKEN_COOKIE);
        if (cookie == null) {
            cookie = getCookie(request, SESSION_TOKEN_COOKIE);
        }
        return cookie;
    }


    /**
     * Get a cookie by name from the given servlet request.
     *
     * @param request    the request containing the cookie.
     * @param cookieName the case-sensitive name of the cookie to get.
     * @return the resulting Cookie; or null, if not found.
     */
    public static Cookie getCookie(HttpServletRequest request, String cookieName) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals(cookieName)) {
                    String value = cookie.getValue();
                    if (StringUtils.hasText(value)) {
                        return cookie;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Create cookies using the provided values.
     *
     * @param request           the request we are handling.
     * @param accessToken       the access token and enclosed refresh token for our cookies.
     * @param rememberMe        whether the user had originally checked "remember me".
     */
    public void createCookies(HttpServletRequest request, OAuth2AccessToken accessToken, boolean rememberMe, HttpServletResponse response) {
        OAuth2Cookies oAuth2Cookies = new OAuth2Cookies();
        String domain = oAuth2Properties.getWebClientConfiguration().getCookieDomain();
        log.debug("creating cookies for domain {}", domain);

        Cookie accessTokenCookie = createTokenCookie(ACCESS_TOKEN_COOKIE,accessToken.getValue(),rememberMe);
        setCookieProperties(accessTokenCookie, request.isSecure(), domain);
        log.debug("created access token cookie '{}',age: {}", accessTokenCookie.getName(),accessTokenCookie.getMaxAge());

        OAuth2RefreshToken refreshToken = accessToken.getRefreshToken();
        Cookie refreshTokenCookie = createTokenCookie(REFRESH_TOKEN_COOKIE,refreshToken.getValue(), rememberMe);
        setCookieProperties(refreshTokenCookie, request.isSecure(), domain);
        log.debug("created refresh token cookie '{}', age: {}", refreshTokenCookie.getName(), refreshTokenCookie.getMaxAge());
        //把accessTokenCookie和refreshTokenCookie写入到response中
        oAuth2Cookies.setCookies(accessTokenCookie, refreshTokenCookie);
        oAuth2Cookies.addCookiesTo(response);
        //additional-info Cookie写入到response中
        writeAdditionalInfo2Cookie("additional-info",accessToken,response);
    }

    private void writeAdditionalInfo2Cookie(String cookieName,OAuth2AccessToken oauth2AccessToken,HttpServletResponse response) {
        String domain = oAuth2Properties.getWebClientConfiguration().getCookieDomain();
        JSONObject additionalInfoObj = new JSONObject(oauth2AccessToken.getAdditionalInformation());
        String additionalInfo = "";
        try{
            additionalInfo = URLEncoder.encode(additionalInfoObj.toJSONString(),"utf-8");
        }catch (Exception ex){
            log.error(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())+",写登录信息到返回response失败"  + ex.getMessage());
        }

        Cookie additionalInfoCookie = new Cookie(cookieName,additionalInfo);
        additionalInfoCookie.setPath("/");
        //设置下过期时间为刷新token的过期时间
        Integer exp = getClaim(oauth2AccessToken.getRefreshToken().getValue(), AccessTokenConverter.EXP, Integer.class);
        int maxAge = -1;
        if (exp != null) {
            int now = (int) (System.currentTimeMillis() / 1000L);
            maxAge = exp - now;
        }
        additionalInfoCookie.setMaxAge(maxAge);
        if (org.apache.commons.lang.StringUtils.isNotBlank(domain)){
            additionalInfoCookie.setDomain(domain);
        }
        response.addCookie(additionalInfoCookie);
    }

    /**
     * Create a cookie out of the given refresh token.
     * Refresh token cookies contain the base64 encoded refresh token (a JWT token).
     * They also contain a hint whether the refresh token was for remember me or not.
     * If not, then the cookie will be prefixed by the timestamp it was created at followed by a pipe '|'.
     * This gives us the chance to expire session cookies regardless of the token duration.
     */
    private Cookie createTokenCookie(String cookieName, String tokenValue, boolean rememberMe) {
        int maxAge = -1;
        Integer exp = getClaim(tokenValue, AccessTokenConverter.EXP, Integer.class);
        if (exp != null) {
            int now = (int) (System.currentTimeMillis() / 1000L);
            maxAge = exp - now;
        }
        Cookie tokenCookie = new Cookie(cookieName, tokenValue);
        tokenCookie.setMaxAge(maxAge);
        return tokenCookie;
    }

    /**
     * Returns true if the refresh token cookie was set with remember me checked.
     * We can recognize this by the name of the cookie.
     *
     * @param refreshTokenCookie the cookie holding the refresh token.
     * @return true, if it was set persistently (i.e. for "remember me").
     */
    public static boolean isRememberMe(Cookie refreshTokenCookie) {
        return refreshTokenCookie.getName().equals(REFRESH_TOKEN_COOKIE);
    }

    /**
     * Extracts the refresh token from the refresh token cookie.
     * Since we encode additional information into the cookie, this needs to be called to get
     * hold of the enclosed JWT.
     *
     * @param refreshCookie the cookie we store the value in.
     * @return the refresh JWT from the cookie.
     */
    public static String getRefreshTokenValue(Cookie refreshCookie) {
        String value = refreshCookie.getValue();
        int i = value.indexOf('|');
        if (i > 0) {
            return value.substring(i + 1);
        }
        return value;
    }

    /**
     * Checks if the refresh token session has expired.
     * Only makes sense for non-persistent cookies, i.e. when remember me was not checked.
     * The motivation for this is that we want to throw out a user after a while if he's inactive.
     * We cannot do this via refresh token validity because that one is also used for remember me.
     *
     * @param refreshCookie the refresh token cookie to check.
     * @return true, if the session is expired.
     */
    public boolean isSessionExpired(Cookie refreshCookie) {
        if (isRememberMe(refreshCookie)) {       //no session expiration for "remember me"
            return false;
        }
        //read non-remember-me session length in secs
        int validity = oAuth2Properties.getWebClientConfiguration().getSessionTimeoutInSeconds();
        if (validity < 0) {           //no session expiration configured
            return false;
        }
        Integer iat = getClaim(refreshCookie.getValue(), "iat", Integer.class);
        if (iat == null) {           //token creating timestamp in secs is missing, session does not expire
            return false;
        }
        int now = (int) (System.currentTimeMillis() / 1000L);
        int sessionDuration = now - iat;
        log.debug("session duration {} secs, will timeout at {}", sessionDuration, validity);
        return sessionDuration > validity;            //session has expired
    }

    /**
     * Retrieve the given claim from the given token.
     *
     * @param refreshToken the JWT token to examine.
     * @param claimName    name of the claim to get.
     * @param clazz        the Class we expect to find there.
     * @return the desired claim.
     * @throws InvalidTokenException if we cannot find the claim in the token or it is of wrong type.
     */
    @SuppressWarnings("unchecked")
    private <T> T getClaim(String refreshToken, String claimName, Class<T> clazz) {
        Jwt jwt = JwtHelper.decode(refreshToken);
        String claims = jwt.getClaims();
        Map<String, Object> claimsMap = jsonParser.parseMap(claims);
        Object claimValue = claimsMap.get(claimName);
        if (claimValue == null) {
            return null;
        }
        if (!clazz.isAssignableFrom(claimValue.getClass())) {
            throw new InvalidTokenException("claim is not of expected type: " + claimName);
        }
        return (T) claimValue;
    }

    /**
     * Set cookie properties of access and refresh tokens.
     *
     * @param cookie   the cookie to modify.
     * @param isSecure whether it is coming from a secure request.
     * @param domain   the domain for which the cookie is valid. If null, then will fall back to default.
     */
    private void setCookieProperties(Cookie cookie, boolean isSecure, String domain) {
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setSecure(isSecure);       //if the request comes per HTTPS set the secure option on the cookie
        if (domain != null) {
            cookie.setDomain(domain);
        }
    }

    /**
     * Logs the user out by clearing all cookies.
     *
     * @param httpServletRequest  the request containing the Cookies.
     * @param httpServletResponse the response used to clear them.
     */
    public void clearCookies(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        String domain = getCookieDomain(httpServletRequest);
        for (String cookieName : COOKIE_NAMES) {
            clearCookie(httpServletRequest, httpServletResponse, domain, cookieName);
        }
    }

    private void clearCookie(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                             String domain, String cookieName) {
        Cookie cookie = new Cookie(cookieName, "");
        setCookieProperties(cookie, httpServletRequest.isSecure(), domain);
        cookie.setMaxAge(0);
        httpServletResponse.addCookie(cookie);
        log.debug("clearing cookie {}", cookie.getName());
    }

    /**
     * Returns the top level domain of the server from the request. This is used to limit the Cookie
     * to the top domain instead of the full domain name.
     * <p>
     * A lot of times, individual gateways of the same domain get their own subdomain but authentication
     * shall work across all subdomains of the top level domain.
     * <p>
     * For example, when sending a request to <code>app1.domain.com</code>,
     * this returns <code>.domain.com</code>.
     *
     * @param request the HTTP request we received from the client.
     * @return the top level domain to set the cookies for.
     * Returns null if the domain is not under a public suffix (.com, .co.uk), e.g. for localhost.
     */
    private String getCookieDomain(HttpServletRequest request) {
        String domain = oAuth2Properties.getWebClientConfiguration().getCookieDomain();
        if (domain != null) {
            return domain;
        }
        return null;
    }

    /**
     * Strip our token cookies from the array.
     *
     * @param cookies the cookies we receive as input.
     * @return the new cookie array without our tokens.
     */
    public Cookie[] stripCookies(Cookie[] cookies) {
        CookieCollection cc = new CookieCollection(cookies);
        if (cc.removeAll(COOKIE_NAMES)) {
            return cc.toArray();
        }
        return cookies;
    }


}