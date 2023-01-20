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

package org.springframework.aop.framework.autoproxy;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving standard Spring Advisors from a BeanFactory,
 * for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AbstractAdvisorAutoProxyCreator
 */
public class BeanFactoryAdvisorRetrievalHelper {

	private static final Log logger = LogFactory.getLog(BeanFactoryAdvisorRetrievalHelper.class);

	private final ConfigurableListableBeanFactory beanFactory;

	@Nullable
	private volatile String[] cachedAdvisorBeanNames;


	/**
	 * Create a new BeanFactoryAdvisorRetrievalHelper for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAdvisorRetrievalHelper(ConfigurableListableBeanFactory beanFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		this.beanFactory = beanFactory;
	}


	/**
	 * Find all eligible Advisor beans in the current bean factory,
	 * ignoring FactoryBeans and excluding beans that are currently in creation.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> findAdvisorBeans() {
		// Determine list of advisor bean names, if not cached already.
		//获取已找到的Advisor的beanName数组
		String[] advisorNames = this.cachedAdvisorBeanNames;
		//如果为null，表示没有缓存
		if (advisorNames == null) {
			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the auto-proxy creator apply to them!
			/*
			 * 获取全部Advisor类型的beanName数组
			 * 这个方法我们在"IoC容器初始化(6)"的文章中已经讲过了
			 */
			advisorNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
					this.beanFactory, Advisor.class, true, false);
			//赋值给cachedAdvisorBeanNames，缓存起来，下次直接从缓存获取
			this.cachedAdvisorBeanNames = advisorNames;
		}
		//如果bean工厂中没有任何的Advisor，那么直接返回空集合
		if (advisorNames.length == 0) {
			return new ArrayList<>();
		}
		//advisors用于保存找到的Advisor
		List<Advisor> advisors = new ArrayList<>();
		//遍历advisorNames数组
		for (String name : advisorNames) {
			/*
			 * 根据通知器的beanName判断通知器bean是否合格，如果合格才能算作候选Advisor。
			 * 该方法在BeanFactoryAdvisorRetrievalHelper中默认返回true，被子类BeanFactoryAdvisorRetrievalHelperAdapter重写，
			 * 继而调用AbstractAdvisorAutoProxyCreator的isEligibleAdvisorBean方法判断，AbstractAdvisorAutoProxyCreator的isEligibleAdvisorBean方法同样默认返回true
			 *
			 * isEligibleAdvisorBean被子类DefaultAdvisorAutoProxyCreator和InfrastructureAdvisorAutoProxyCreator重写
			 * 而AspectJAwareAdvisorAutoProxyCreator和AnnotationAwareAspectJAutoProxyCreator则没有重写
			 *
			 * 因此该方法主要是AspectJAwareAdvisorAutoProxyCreator和AnnotationAwareAspectJAutoProxyCreator这两个自动代理创建者会用到
			 * 而AspectJAwareAdvisorAutoProxyCreator和AnnotationAwareAspectJAutoProxyCreator这两个自动代理创建者默认始终返回true
			 */
			if (isEligibleBean(name)) {
				//当前切面bean是否正在创建中，如果是，那么跳过
				if (this.beanFactory.isCurrentlyInCreation(name)) {
					if (logger.isTraceEnabled()) {
						logger.trace("Skipping currently created advisor '" + name + "'");
					}
				}
				else {
					try {
						/*
						 * 通过beanFactory.getBean方法初始化这个切面bean，加入advisors集合中
						 * getBean方法是IoC容器初始化的核心方法，我们在前面的文章中已花费了大量时间讲解
						 */
						advisors.add(this.beanFactory.getBean(name, Advisor.class));
					}
					catch (BeanCreationException ex) {
						Throwable rootCause = ex.getMostSpecificCause();
						if (rootCause instanceof BeanCurrentlyInCreationException) {
							BeanCreationException bce = (BeanCreationException) rootCause;
							String bceBeanName = bce.getBeanName();
							if (bceBeanName != null && this.beanFactory.isCurrentlyInCreation(bceBeanName)) {
								if (logger.isTraceEnabled()) {
									logger.trace("Skipping advisor '" + name +
											"' with dependency on currently created bean: " + ex.getMessage());
								}
								// Ignore: indicates a reference back to the bean we're trying to advise.
								// We want to find advisors other than the currently created bean itself.
								continue;
							}
						}
						throw ex;
					}
				}
			}
		}
		return advisors;
	}

	/**
	 * Determine whether the aspect bean with the given name is eligible.
	 * <p>The default implementation always returns {@code true}.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
