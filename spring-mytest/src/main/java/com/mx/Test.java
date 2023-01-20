package com.mx;

import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/*** @author smart哥 ** 码炫课堂java技术交流q群：963060292 */
public class Test {
	public static void main(String[] args) {
		System.out.println("hah");
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(JavaConfig.class);
		context.getBeanFactory().registerSingleton("user",new User());
		User user = (User) context.getBean("user");
		System.out.println(user.toString());
	}
}
