/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aopalliance.intercept.Interceptor;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionAwareMethodMatcher;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.lang.Nullable;

/**
 * A simple but definitive way of working out an advice chain for a Method,
 * given an {@link Advised} object. Always rebuilds each advice chain;
 * caching can be provided by subclasses.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Adrian Colyer
 * @since 2.0.3
 */
@SuppressWarnings("serial")
public class DefaultAdvisorChainFactory implements AdvisorChainFactory, Serializable {

	//     * 确定给定方法的拦截器链列表
	@Override
	public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
			Advised config, Method method, @Nullable Class<?> targetClass) {

		// This is somewhat tricky... We have to process introductions first,
		// but we need to preserve order in the ultimate list.
		//获取Advisor适配器注册表，默认获取的是一个DefaultAdvisorAdapterRegistry类型的单例
		AdvisorAdapterRegistry registry = GlobalAdvisorAdapterRegistry.getInstance();
		//从proxyFactory中获取此前设置的全部advisors
		Advisor[] advisors = config.getAdvisors();
		//拦截器列表
		List<Object> interceptorList = new ArrayList<>(advisors.length);
		//实际目标类型
		Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass());
		//是否具有引介增强
		Boolean hasIntroductions = null;
		//遍历advisors集合
		for (Advisor advisor : advisors) {
			//如果属于PointcutAdvisor，即切入点通知器。比如<aop:advisor/>标签的DefaultBeanFactoryPointcutAdvisor
			//以及通知标签的AspectJPointcutAdvisor，都是PointcutAdvisor类型
			if (advisor instanceof PointcutAdvisor) {
				// Add it conditionally.
				PointcutAdvisor pointcutAdvisor = (PointcutAdvisor) advisor;
				//判断切入点增强器是否匹配当前方法
				if (config.isPreFiltered() || pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)) {
					//获取方法匹配器，比如AspectJExpressionPointcut切入点
					MethodMatcher mm = pointcutAdvisor.getPointcut().getMethodMatcher();
					boolean match;
					//AspectJExpressionPointcut属于IntroductionAwareMethodMatcher，适配引介增强
					if (mm instanceof IntroductionAwareMethodMatcher) {
						if (hasIntroductions == null) {
							hasIntroductions = hasMatchingIntroductions(advisors, actualClass);
						}
						match = ((IntroductionAwareMethodMatcher) mm).matches(method, actualClass, hasIntroductions);
					}
					else {
						match = mm.matches(method, actualClass);
					}
					//如果匹配当前方法
					if (match) {
						//将当前advisor通知器转换为MethodInterceptor方法拦截器
						MethodInterceptor[] interceptors = registry.getInterceptors(advisor);
						//是否需要动态匹配，比如expression中使用args()等可以传递参数的PCD类型，那么就需要动态匹配，可以匹配的某个参数的运行时传递的类型及其子类型
						//动态匹配开销比较大，一般都不是也不需要使用
						if (mm.isRuntime()) {
							// Creating a new object instance in the getInterceptors() method
							// isn't a problem as we normally cache created chains.
							for (MethodInterceptor interceptor : interceptors) {
								//封装成为InterceptorAndDynamicMethodMatcher
								interceptorList.add(new InterceptorAndDynamicMethodMatcher(interceptor, mm));
							}
						}
						else {
							interceptorList.addAll(Arrays.asList(interceptors));
						}
					}
				}
			}
			//如果属于IntroductionAdvisor，即引介增强通知器
			//比如<aop:declare-parents/>标签的DeclareParentsAdvisor就是IntroductionAdvisor类型
			else if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				if (config.isPreFiltered() || ia.getClassFilter().matches(actualClass)) {
					Interceptor[] interceptors = registry.getInterceptors(advisor);
					interceptorList.addAll(Arrays.asList(interceptors));
				}
			}
			//否则，默认匹配
			else {
				Interceptor[] interceptors = registry.getInterceptors(advisor);
				interceptorList.addAll(Arrays.asList(interceptors));
			}
		}

		return interceptorList;
	}

	/**
	 * Determine whether the Advisors contain matching introductions.
	 * /**
	 *      * 确定Advisors是否包含匹配的引介增强
	 *
	 */
	private static boolean hasMatchingIntroductions(Advisor[] advisors, Class<?> actualClass) {
		for (Advisor advisor : advisors) {
			if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				//引介匹配，也就是比较types-matching属性
				if (ia.getClassFilter().matches(actualClass)) {
					return true;
				}
			}
		}
		return false;
	}

}
