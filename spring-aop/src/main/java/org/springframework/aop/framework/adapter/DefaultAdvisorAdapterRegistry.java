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

package org.springframework.aop.framework.adapter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/**
 * Default implementation of the {@link AdvisorAdapterRegistry} interface.
 * Supports {@link org.aopalliance.intercept.MethodInterceptor},
 * {@link org.springframework.aop.MethodBeforeAdvice},
 * {@link org.springframework.aop.AfterReturningAdvice},
 * {@link org.springframework.aop.ThrowsAdvice}.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 */
@SuppressWarnings("serial")
public class DefaultAdvisorAdapterRegistry implements AdvisorAdapterRegistry, Serializable {

	private final List<AdvisorAdapter> adapters = new ArrayList<>(3);


	/**
	 * Create a new DefaultAdvisorAdapterRegistry, registering well-known adapters.
	 */
	public DefaultAdvisorAdapterRegistry() {
		registerAdvisorAdapter(new MethodBeforeAdviceAdapter());
		registerAdvisorAdapter(new AfterReturningAdviceAdapter());
		registerAdvisorAdapter(new ThrowsAdviceAdapter());
	}


	@Override
	public Advisor wrap(Object adviceObject) throws UnknownAdviceTypeException {
		if (adviceObject instanceof Advisor) {
			return (Advisor) adviceObject;
		}
		if (!(adviceObject instanceof Advice)) {
			throw new UnknownAdviceTypeException(adviceObject);
		}
		Advice advice = (Advice) adviceObject;
		if (advice instanceof MethodInterceptor) {
			// So well-known it doesn't even need an adapter.
			return new DefaultPointcutAdvisor(advice);
		}
		for (AdvisorAdapter adapter : this.adapters) {
			// Check that it is supported.
			if (adapter.supportsAdvice(advice)) {
				return new DefaultPointcutAdvisor(advice);
			}
		}
		throw new UnknownAdviceTypeException(advice);
	}

	@Override
	public MethodInterceptor[] getInterceptors(Advisor advisor) throws UnknownAdviceTypeException {
		//拦截器集合
		List<MethodInterceptor> interceptors = new ArrayList<>(3);
		//获取通知器内部的通知
		//<aop:before/> -> AspectJMethodBeforeAdvice -> MethodBeforeAdvice
		//<aop:after/> -> AspectJAfterAdvice -> MethodInterceptor
		//<aop:after-returning/> -> AspectJAfterReturningAdvice -> AfterReturningAdvice
		//<aop:after-throwing/> -> AspectJAfterThrowingAdvice -> MethodInterceptor
		//<aop:around/> -> AspectJAroundAdvice -> MethodInterceptor
		Advice advice = advisor.getAdvice();
		//如果通知本身就属于MethodInterceptor
		//AspectJAfterAdvice、AspectJAfterThrowingAdvice、AspectJAroundAdvice它们都属于MethodInterceptor
		if (advice instanceof MethodInterceptor) {
			//直接添加当前通知
			interceptors.add((MethodInterceptor) advice);
		}
		//尝试通过适配器转换
		for (AdvisorAdapter adapter : this.adapters) {
			//如果该是配置支持当前通知
			if (adapter.supportsAdvice(advice)) {
				//MethodBeforeAdviceAdapter支持AspectJMethodBeforeAdvice通知，适配成MethodBeforeAdviceInterceptor
				//AfterReturningAdviceAdapter支持AspectJAfterReturningAdvice通知，适配成AfterReturningAdviceInterceptor
				interceptors.add(adapter.getInterceptor(advisor));
			}
		}
		//如果为空集合，抛出异常
		if (interceptors.isEmpty()) {
			throw new UnknownAdviceTypeException(advisor.getAdvice());
		}
		return interceptors.toArray(new MethodInterceptor[0]);
	}

	@Override
	public void registerAdvisorAdapter(AdvisorAdapter adapter) {
		this.adapters.add(adapter);
	}

}
