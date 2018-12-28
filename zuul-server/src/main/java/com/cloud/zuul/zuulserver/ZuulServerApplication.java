package com.cloud.zuul.zuulserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;

@SpringBootApplication
@EnableZuulProxy
@EnableOAuth2Sso
public class ZuulServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZuulServerApplication.class, args);
	}

	/**
	 * 资源过滤器
	 * @return 资源过滤器
	 */
/*	@Bean
	public AccessFilter accessFilter(){
		return new AccessFilter();
	}

	*//**
	 * Cors过滤器
	 * @return
	 *//*
	@Bean
	public CorsZuulFilter corsZuulFilter(){
		return new CorsZuulFilter();
	}*/
}

