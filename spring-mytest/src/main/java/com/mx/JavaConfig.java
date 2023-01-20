package com.mx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * @Author pengwen
 * @DESCRIPTION
 * @Create 2022/8/18
 */
@Configuration
@ComponentScan
public class JavaConfig {
	@Bean()
	public User user() {
		return new User("001", "smart哥");
	}
}
