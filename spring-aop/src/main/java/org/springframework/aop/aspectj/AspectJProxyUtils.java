/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.aop.aspectj;

import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;

/**
 * Utility methods for working with AspectJ proxies.
 *
 * @author Rod Johnson
 * @author Ramnivas Laddad
 * @author Juergen Hoeller
 * @since 2.0
 */
public abstract class AspectJProxyUtils {

	/**
	 * Add special advisors if necessary to work with a proxy chain that contains AspectJ advisors:
	 * concretely, {@link ExposeInvocationInterceptor} at the beginning of the list.
	 * <p>This will expose the current Spring AOP invocation (necessary for some AspectJ pointcut
	 * matching) and make available the current AspectJ JoinPoint. The call will have no effect
	 * if there are no AspectJ advisors in the advisor chain.
	 * @param advisors the advisors available
	 * @return {@code true} if an {@link ExposeInvocationInterceptor} was added to the list,
	 * otherwise {@code false}
	 * 添加一个特殊的Advisor到Advisors链头部，使用 AspectJ 切入点表达式和使用 AspectJ 样式的Advice时都需要添加
	 *  * 添加的是ExposeInvocationInterceptor.ADVISOR，实际类型是一个DefaultPointcutAdvisor类型的单例实例
	 *  * 它内部保存了ExposeInvocationInterceptor拦截器的单例实例，当进行拦截时Advisors链的第一个方法就是调用该拦截器的invoke方法
	 *  * 这个拦截器的作用就是暴露当前MethodInvocation，实际操作就是将当前MethodInvocation存入一个ThreadLocal本地线程变量中，
	 *  * 后续的拦截器可以直接通过ExposeInvocationInterceptor.currentInvocation()静态方法快速获取当前的MethodInvocation
	 */
	public static boolean makeAdvisorChainAspectJCapableIfNecessary(List<Advisor> advisors) {
		// Don't add advisors to an empty list; may indicate that proxying is just not required
		//不要将advisor添加到空列表中，这可能表示不需要代理
		if (!advisors.isEmpty()) {
			boolean foundAspectJAdvice = false;
			for (Advisor advisor : advisors) {
				// Be careful not to get the Advice without a guard, as this might eagerly
				// instantiate a non-singleton AspectJ aspect...
				// 是否存在AspectJAdvice类型的Advice，一般都存在
				if (isAspectJAdvice(advisor)) {
					foundAspectJAdvice = true;
					break;
				}
			}
			//如果存在AspectJAdvice类型的Advisor，并且不包含要添加的ExposeInvocationInterceptor.ADVISOR单例
			if (foundAspectJAdvice && !advisors.contains(ExposeInvocationInterceptor.ADVISOR)) {
				//将ExposeInvocationInterceptor.ADVISOR加入到advisors链的头部，在拦截时将会第一个调用
				advisors.add(0, ExposeInvocationInterceptor.ADVISOR);
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether the given Advisor contains an AspectJ advice.
	 * @param advisor the Advisor to check
	 */
	private static boolean isAspectJAdvice(Advisor advisor) {
		return (advisor instanceof InstantiationModelAwarePointcutAdvisor ||
				advisor.getAdvice() instanceof AbstractAspectJAdvice ||
				(advisor instanceof PointcutAdvisor &&
						((PointcutAdvisor) advisor).getPointcut() instanceof AspectJExpressionPointcut));
	}

}
