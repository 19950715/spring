/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.aop.aspectj.autoproxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aspectj.util.PartialOrder;
import org.aspectj.util.PartialOrder.PartialComparable;

import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJPointcutAdvisor;
import org.springframework.aop.aspectj.AspectJProxyUtils;
import org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * {@link org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator}
 * subclass that exposes AspectJ's invocation context and understands AspectJ's rules
 * for advice precedence when multiple pieces of advice come from the same aspect.
 *
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @since 2.0
 */
@SuppressWarnings("serial")
public class AspectJAwareAdvisorAutoProxyCreator extends AbstractAdvisorAutoProxyCreator {

	private static final Comparator<Advisor> DEFAULT_PRECEDENCE_COMPARATOR = new AspectJPrecedenceComparator();


	/**
	 * Sort the supplied {@link Advisor} instances according to AspectJ precedence.
	 * <p>If two pieces of advice come from the same aspect, they will have the same
	 * order. Advice from the same aspect is then further ordered according to the
	 * following rules:
	 * <ul>
	 * <li>If either of the pair is <em>after</em> advice, then the advice declared
	 * last gets highest precedence (i.e., runs last).</li>
	 * <li>Otherwise the advice declared first gets highest precedence (i.e., runs
	 * first).</li>
	 * </ul>
	 * <p><b>Important:</b> Advisors are sorted in precedence order, from highest
	 * precedence to lowest. "On the way in" to a join point, the highest precedence
	 * advisor should run first. "On the way out" of a join point, the highest
	 * precedence advisor should run last.
	 */
	@Override
	protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
		List<PartiallyComparableAdvisorHolder> partiallyComparableAdvisors = new ArrayList<>(advisors.size());
		//Advisor封装成为PartiallyComparableAdvisorHolder对象，它是AspectJAwareAdvisorAutoProxyCreator的内部类
		//并将加入PartiallyComparableAdvisorHolder对象partiallyComparableAdvisors集合中，使用AspectJPrecedenceComparator比较器
		for (Advisor advisor : advisors) {
			partiallyComparableAdvisors.add(
					new PartiallyComparableAdvisorHolder(advisor, DEFAULT_PRECEDENCE_COMPARATOR));
		}
		/*
		 * 通过PartialOrder.sort排序，这里的排序仅仅是采用的"偏序排序"，并且采用了图形结构
		 */
		List<PartiallyComparableAdvisorHolder> sorted = PartialOrder.sort(partiallyComparableAdvisors);
		/*还原排序之后的数据*/
		if (sorted != null) {
			List<Advisor> result = new ArrayList<>(advisors.size());
			for (PartiallyComparableAdvisorHolder pcAdvisor : sorted) {
				result.add(pcAdvisor.getAdvisor());
			}
			return result;
		}
		else {
			//如果无法排序，那么采用父类的排序方式
			return super.sortAdvisors(advisors);
		}
	}

	/**
	 * Add an {@link ExposeInvocationInterceptor} to the beginning of the advice chain.
	 * <p>This additional advice is needed when using AspectJ pointcut expressions
	 * and when using AspectJ-style advice.
	 * 添加一个特殊的Advisor添加到Advisors链头部，使用一个特殊的拦截器ExposeInvocationInterceptor
	 *  * 用于向后面的拦截器暴露当前的  MethodInvocation
	 */
	@Override
	protected void extendAdvisors(List<Advisor> candidateAdvisors) {
		AspectJProxyUtils.makeAdvisorChainAspectJCapableIfNecessary(candidateAdvisors);
	}

	@Override
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		// TODO: Consider optimization by caching the list of the aspect names
		/*
		 * 查找bean工厂的所有Advisor类型的通知器bean定义并且初始化，返回Advisor实例的集合。在解析<aop:config/>标签时，
		 * <aop:advisor/>标签的DefaultBeanFactoryPointcutAdvisor，<aop:declare-parents/>标签的DeclareParentsAdvisor，
		 * 通知标签的AspectJPointcutAdvisor，他们都属于Advisor，也就是通知器，通常一个切入点和一个通知方法就组成通知器
		 */
		List<Advisor> candidateAdvisors = findCandidateAdvisors();
		/*
		 * 如果存在AspectJPointcutAdvisor类型的通知器实例，并且当前的bean属于这个通知器的切面方法类bean
		 * 那么不应该拦截切面方法类的方法，直接返回true，表示跳过
		 */
		for (Advisor advisor : candidateAdvisors) {
			if (advisor instanceof AspectJPointcutAdvisor &&
					((AspectJPointcutAdvisor) advisor).getAspectName().equals(beanName)) {
				return true;
			}
		}
		//否则调用父类AbstractAutoProxyCreator的方法
		return super.shouldSkip(beanClass, beanName);
	}


	/**
	 * Implements AspectJ's {@link PartialComparable} interface for defining partial orderings.
	 */
	private static class PartiallyComparableAdvisorHolder implements PartialComparable {

		private final Advisor advisor;

		private final Comparator<Advisor> comparator;

		public PartiallyComparableAdvisorHolder(Advisor advisor, Comparator<Advisor> comparator) {
			this.advisor = advisor;
			this.comparator = comparator;
		}

		@Override
		public int compareTo(Object obj) {
			Advisor otherAdvisor = ((PartiallyComparableAdvisorHolder) obj).advisor;
			return this.comparator.compare(this.advisor, otherAdvisor);
		}

		@Override
		public int fallbackCompareTo(Object obj) {
			return 0;
		}

		public Advisor getAdvisor() {
			return this.advisor;
		}

		@Override
		public String toString() {
			Advice advice = this.advisor.getAdvice();
			StringBuilder sb = new StringBuilder(ClassUtils.getShortName(advice.getClass()));
			boolean appended = false;
			if (this.advisor instanceof Ordered) {
				sb.append(": order = ").append(((Ordered) this.advisor).getOrder());
				appended = true;
			}
			if (advice instanceof AbstractAspectJAdvice) {
				sb.append(!appended ? ": " : ", ");
				AbstractAspectJAdvice ajAdvice = (AbstractAspectJAdvice) advice;
				sb.append("aspect name = ");
				sb.append(ajAdvice.getAspectName());
				sb.append(", declaration order = ");
				sb.append(ajAdvice.getDeclarationOrder());
			}
			return sb.toString();
		}
	}

}
