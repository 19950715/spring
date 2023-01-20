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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		//要执行回调方法和已执行回调方法的beanName集合，用于防止重复回调
		Set<String> processedBeans = new HashSet<>();

		//1.判断beanFactory,是否是BeanDefinitionRegistry接口的实现类(true)
		/*beanFactory是否属于BeanDefinitionRegistry，一般都是，所有都会走这一个逻辑*/
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			//1.1:存放普通的BeanFactoryPostProcessor
			//这个集合保存BeanFactoryPostProcessor类型的后置处理器实例（用于最后执行postProcessBeanFactory回调方法）
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			//1.2:存放BeanDefinitionRegistryPostProcessor类型的BeanFactoryPostProcessor
			//这个集合保存BeanDefinitionRegistryPostProcessor类型的后置处理器实例（用于最后执行postProcessBeanFactory回调方法）
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			/*
			 * 1 首先遍历、处理beanFactoryPostProcessors集合
			 * 对于BeanDefinitionRegistryPostProcessor类型的对象回调postProcessBeanDefinitionRegistry方法
			 * 并加入registryProcessors集合中，非这个类型的BeanFactoryPostProcessor加入regularPostProcessors集合中
			 */
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				/*1.1 如果属于BeanDefinitionRegistryPostProcessor类型*/
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					//1.3.l:首先执行BeanDefinitionRegistryPostProcessor中的方法postProcessBeanDefinitionRegistry
					/*1.1.1 按照遍历顺序，回调postProcessBeanDefinitionRegistry方法*/
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					//1.3.2:然后再将registryProcessor存放起来（方便后续执行方法postProcessBeanFactory)
					//1.1.2 加入到registryProcessors集合中(用于最后执行postProcessBeanFactory回调方法)
					registryProcessors.add(registryProcessor);
				}
				/*1.2 如果不属于BeanDefinitionRegistryPostProcessor类型*/
				else {
					//1.3.1:如果是普通的工厂后处理器，那就只实现了一个接口BeanFactoryPostProcessor
					//存放到普通工厂后处理器的集合中
					//1.2.1 加入到regularPostProcessors集合中
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			//1.4:用于保存当前需要执行的BeanDefinitionRegistryPostProcessor(每处理完一批，会阶段性清空一批)
			//这个集合临时保存当前准备创建并执行回调的BeanDefinitionRegistryPostProcessor类型的后置处理器实例，毁掉完毕即清理
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			//beanFactory.getBeanNamesForType---返回一个bdrp名字的集合---通过名字去实例化一个bdrp---当spring执行到这里的时候并没有实例化bdMap当中的bd
			//在这通过mergedBeanDefinitions.put方法进行了合并bd  因为这里不能拿原始bd去判断类型，bd有可能是父类设置了setBeanClass 子类没有 用继承父类的beanclass 所以这里必须合并
			//spirng----查找--BeanDefinitionRegistryPostProcessor---合并所有扫描以及api注册和spring内置的bd-
			// 在后面还要合并  因为可能在-执行之中-----ImportI-----或者---A-----impl-----------BeanFactoryPostProcessor 改变了bd
			//也有地方对mergedBeanDefinitions进行了remove  就是为了防止后面改变了bd  让重新合并bd
			//1.5:处理beanFactory中，既实现接口BeanDefinitionRegistryPostProcessor,又实现接口PriorityOrdered的实现类
			//1):从beanFactory中，获取类型为BeanDefinitionRegistryPostProcessor的所有bean

			/*
			 * 2 对于beanFactory中，所有实现了PriorityOrdered接口的BeanDefinitionRegistryPostProcessor类型的bean定义进行实例化
			 * 随后对这一批BeanDefinitionRegistryPostProcessor实例进行排序，最后按照排序顺序从前向后回调postProcessBeanDefinitionRegistry方法
			 */
			// 从beanFactory中获取所有BeanDefinitionRegistryPostProcessor类型的bean定义的名称数组
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			//遍历beanFactory中的BeanDefinitionRegistryPostProcessor类型的bean定义的名称数组
			for (String ppName : postProcessorNames) {
				//如果该名称的bean定义还实现了PriorityOrdered接口
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// beanFactory.getBean----直接从容器当中拿
					// 如果拿不到---通过名字实例化这个bdrpp

					//当前beanName的BeanDefinitionRegistryPostProcessor实例加入到currentRegistryProcessors集合
					//这个getBean方法实际上已经将bean实例创建出来了
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					//1.l.2):记录类型为BeanDefinitionRegistryPostProcessor的bean的名称（避免后续相同bean重复被执行了）
					//加入到processedBeans集合
					processedBeans.add(ppName);
				}
			}
			//到这一步currentRegistryProcessors集合的元素都是实现了PriorityOrdered接口的类型实例

			//这里对currentRegistryProcessors集合的元素根据Ordered顺序进行排序
			//2):根据Priorityordered.或ordered.接口进行排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			//3):添加到registryProcessors集合中（方便后续执行方法postProcessBeanFactory)
			//currentRegistryProcessors整体加入到registryProcessors集合中(用于最后执行postProcessBeanFactory回调方法)
			registryProcessors.addAll(currentRegistryProcessors);
			//执行bdrpp集合
			//4):执行currentRegistryProcessors中，第一阶段的方法：postProcessBeanDefinitionRegistry 完成了所谓的扫描和parse
			//对于currentRegistryProcessors集合中的已排序的BeanDefinitionRegistryPostProcessor按照顺序从前向后回调postProcessBeanDefinitionRegistry方法
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			//5):执行完成后，清空，准备下一轮
			//回调完毕之后清空currentRegistryProcessors集合
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			//1.6:处理beanFactory中，既实现接口BeanDefinitionRegistryPostProcessor,又实现接口Ordered的实现类
			//以下操作和1.5一致，唯一区别就是PriorityOrdered接口变成了Ordered接口

			/*
			 * 3 对于beanFactory中，所有实现了Ordered接口的BeanDefinitionRegistryPostProcessor类型的bean定义进行实例化（排除上一步调用过的处理器）
			 * 随后对这一批BeanDefinitionRegistryPostProcessor实例进行排序，最后按照排序顺序从前向后回调postProcessBeanDefinitionRegistry方法
			 */

			//重新从beanFactory中获取所有BeanDefinitionRegistryPostProcessor类型的bean定义的名称数组
			//因为此时可能有新增的BeanDefinitionRegistryPostProcessor定义，比如在上面执行的回调方法中又新增了BeanDefinitionRegistryPostProcessor定义
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			//遍历beanFactory中的BeanDefinitionRegistryPostProcessor类型的bean定义的名称数组
			for (String ppName : postProcessorNames) {
				//如果processedBeans集合不包含该名称（防止重复回调），并且该名称的bean定义还实现了PriorityOrdered接口
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					//当前beanName的BeanDefinitionRegistryPostProcessor实例加入到currentRegistryProcessors集合
					//这个getBean方法实际上已经将bean实例创建出来了
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					//加入到processedBeans集合
					processedBeans.add(ppName);
				}
			}

			//到这一步currentRegistryProcessors集合的元素都是实现了Ordered接口的类型实例

			//这里对currentRegistryProcessors集合的元素根据Ordered顺序进行排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			//currentRegistryProcessors整体加入到registryProcessors集合中(用于最后执行postProcessBeanFactory回调方法)
			registryProcessors.addAll(currentRegistryProcessors);
			//2：调用bean工厂后置处理器完成扫描；
			//3：循环解析扫描出来的类信息；
			//4：实例化一个BeanDefinition对象来存储解析出来的信息；
			//5：把实例化好的beanDefinition对象put到beanDefinitionMap当中缓存起来，以便后面实例化bean；

			//对于currentRegistryProcessors集合中的已排序的BeanDefinitionRegistryPostProcessor按照顺序从前向后回调postProcessBeanDefinitionRegistry方法
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			//回调完毕之后清空currentRegistryProcessors集合
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			//1.7:处理beanFactory中，实现接口BeanDefinitionRegistryPostProcessor的实现类
			//同时，这些实现类不能包含前面己经处理了的、也就是实现了PriorityOrdered接口和Ordered接口的那些实现类

			/*
			 * 4 对于beanFactory中，所有剩余的普通的BeanDefinitionRegistryPostProcessor类型的bean定义进行实例化
			 * 随后对这一批BeanDefinitionRegistryPostProcessor实例进行排序，最后按照排序顺序从前向后回调postProcessBeanDefinitionRegistry方法
			 *
			 * 这一步将会一直循环重试，直到bean定义中不再出现新的BeanDefinitionRegistryPostProcessors，
			 * 因为在上面执行的postProcessBeanDefinitionRegistry回调方法中可能又新增了BeanDefinitionRegistryPostProcessor类型的bean定义
			 */
			//重试标志
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				//重新从beanFactory中获取所有BeanDefinitionRegistryPostProcessor类型的bean定义的名称数组
				//因为此时可能有新增的BeanDefinitionRegistryPostProcessor定义，比如在上面执行的回调方法中又新增了BeanDefinitionRegistryPostProcessor定义
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				//直postProcessorNames为空，reiterate保特为false,才推出while循环
				//遍历beanFactory中的BeanDefinitionRegistryPostProcessor类型的bean定义的名称数组
				for (String ppName : postProcessorNames) {
					//如果processedBeans集合不包含该名称（防止重复回调）
					if (!processedBeans.contains(ppName)) {
						//当前beanName的BeanDefinitionRegistryPostProcessor实例加入到currentRegistryProcessors集合
						//这个getBean方法实际上已经将bean实例创建出来了
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						//加入到processedBeans集合
						processedBeans.add(ppName);
						//重试标志改为true，这表示将会重试
						//因为上面的有新加入的BeanDefinitionRegistryPostProcessor定义，而调用它们的回调方法时，可能在回调方法中又新增了BeanDefinitionRegistryPostProcessor定义
						//因此，只要如果processedBeans集合不包含某些beanName，那么就会进入下一次回调
						reiterate = true;
					}
				}
				//到这一步currentRegistryProcessors集合的元素都是剩下的实例，它们即没有实现Ordered接口也没有实现PriorityOrdered接口
				//或者是在回调方法中新增的BeanDefinitionRegistryPostProcessor定义（它们有可能实现Ordered接口或者PriorityOrdered接口），因此还是需要排序

				//这里对currentRegistryProcessors集合的元素根据Ordered顺序进行排序
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				//currentRegistryProcessors整体加入到registryProcessors集合中(用于最后执行postProcessBeanFactory回调方法)
				registryProcessors.addAll(currentRegistryProcessors);
				//对于currentRegistryProcessors集合中的已排序的BeanDefinitionRegistryPostProcessor按照顺序从前向后回调postProcessBeanDefinitionRegistry方法
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				//回调完毕之后清空currentRegistryProcessors集合
				currentRegistryProcessors.clear();
			}

			//到这一步，出了循环，所有的BeanDefinitionRegistryPostProcessor类型的bean定义已被实例化完毕
			//并且它们的postProcessBeanDefinitionRegistry方法已完成回调

			/*
			 * 此时registryProcessors集合中包含beanFactoryPostProcessors和beanFactory中的全部BeanDefinitionRegistryPostProcessor
			 * 此时regularPostProcessors集合中包含beanFactoryPostProcessors中的全部BeanFactoryPostProcessor
			 *
			 * 5 最后，对于registryProcessors中的全部BeanDefinitionRegistryPostProcessor从前向后回调postProcessBeanFactory方法
			 * 对于regularPostProcessors中的全部BeanFactoryPostProcessor从前向后回调postProcessBeanFactory方法
			 */

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			//执行后置处理器完成cglib代理
			//1.8:统一调用BeanDefinitionRegistryPostProcessor类型工厂后处理器的postProcessBeanFactory方法
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			//1.9:统一调用普通类型工厂后处理器的postProcessBeanFactory方法
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}
		/*否则，直接对于beanFactoryPostProcessors集合中的全部实例从前向后回调postProcessBeanFactory方法*/
		else {
			// Invoke factory processors registered with the context instance.
			//1.1:如果beanFactory.不是接口BeanDefinitionRegistry的实现类
			//那就是我们前面看到的普通工厂后处理器，直接调用postProcessBeanFactory方法了
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		//以上环节，参数beanFactoryPostProcessors,以及容器beanFactory中，
		//所有类型为BeanDefinitionRegistryPostProcessor的bean全部都处理了
		//接下来处理beanFactory中纯粹只实现接口BeanFactoryPostProcessor的bean
		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		//2:处理beanFactory中，实现接口BeanFactoryPostProcessor的实现类

		/*
		 * 到这一步，beanFactoryPostProcessors和beanFactory中的全部BeanDefinitionRegistryPostProcessor已完成postProcessBeanDefinitionRegistry方法和postProcessBeanFactory方法的回调
		 * 到这一步，beanFactoryPostProcessors中的全部BeanFactoryPostProcessor已完成postProcessBeanFactory方法的回调
		 *
		 * 到这一步，还剩下beanFactory中的全部BeanFactoryPostProcessor还没有完成postProcessBeanFactory方法的回调，下面的代码就是完成这个工作
		 */

		// 从beanFactory中获取所有BeanFactoryPostProcessor类型的bean定义的名称数组
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		//2.1:存放实现了PriorityOrdered接口的BeanFactoryPostProcessor接口实现类
		//这个集合保存实现了PriorityOrdered接口的BeanFactoryPostProcessor实例
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		//2.2:存放实现了ordered.接口的BeanFactoryPostProcessor接口实现类名称
		//这个集合保存实现了Ordered接口的BeanFactoryPostProcessor的beanName
		List<String> orderedPostProcessorNames = new ArrayList<>();
		//2.3:存放无序的BeanFactoryPostProcessor接口实现类名称
		//这个集合保存普通的BeanFactoryPostProcessor的beanName
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		//遍历postProcessorNames数组，填充数据
		for (String ppName : postProcessorNames) {
			/*如果processedBeans集合包含该名称*/
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
				//直接空实现，因为processedBeans中，记录了前面处理BeanDefinitionRegistryPostProcessorbean的名称
				//表示己经处理过了，这里就不再重复处理
				//什么都不做，防止重复回调
			}
			/*否则，如果该名称的bean定义还实现了PriorityOrdered接口*/
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				//当前beanName的BeanFactoryPostProcessor实例加入到priorityOrderedPostProcessors集合
				//这个getBean方法实际上已经将bean实例创建出来了
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			/*否则，如果该名称的bean定义还实现了Ordered接口*/
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				//当前beanName加入到orderedPostProcessorNames集合
				orderedPostProcessorNames.add(ppName);
			}
			/*否则，当前beanName加入到nonOrderedPostProcessorNames集合*/
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		//2.4:根据PriorityOrdered排序

		/*
		 * 6 对于beanFactory中，所有实现了PriorityOrdered接口的BeanFactoryPostProcessor实例进行排序
		 * 随后按照排序顺序从前向后回调postProcessBeanFactory方法
		 */
		//这里对priorityOrderedPostProcessors集合的元素根据Ordered顺序进行排序
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//2.5:执行工厂后处理器中的postProcessBeanFactory方法
		//对于priorityOrderedPostProcessors集合中的已排序的BeanFactoryPostProcessor按照顺序从前向后回调postProcessBeanFactory方法
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		//2.6:根据刚才记录的实现ordered接口ean的名称，从beanFactory中获取对应bean
		//根据ordered排序并执行postProcessBeanFactory.方法

		/*
		 * 7 对于beanFactory中，所有实现了Ordered接口的BeanFactoryPostProcessor的bean定义进行实例化并且进行排序
		 * 随后按照排序顺序从前向后回调postProcessBeanFactory方法
		 */
		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		//这个集合保存实现了Ordered接口的BeanFactoryPostProcessor实例
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		//遍历orderedPostProcessorNames数组，填充数据
		for (String postProcessorName : orderedPostProcessorNames) {
			//当前beanName的BeanFactoryPostProcessor实例加入到priorityOrderedPostProcessors集合
			//这个getBean方法实际上已经将bean实例创建出来了
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		//这里对orderedPostProcessors集合的元素根据Ordered顺序进行排序
		sortPostProcessors(orderedPostProcessors, beanFactory);
		//对于orderedPostProcessors集合中的已排序的BeanFactoryPostProcessor按照顺序从前向后回调postProcessBeanFactory方法
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		//2.7:根据刚才记录的无序ean的名称，从peanFactory中获取对应bean

		/*
		 * 8 最后，对于beanFactory中，普通的BeanFactoryPostProcessor的bean定义进行实例化
		 * 不需要进行排序了，因为没有实现Ordered接口和PriorityOrdered接口，随后按照遍历顺序从前向后回调postProcessBeanFactory方法
		 */
		// Finally, invoke all other BeanFactoryPostProcessors.
		//这个集合保存普通的BeanFactoryPostProcessor实例
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		//遍历nonOrderedPostProcessorNames数组，填充数据
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			//当前beanName的BeanFactoryPostProcessor实例加入到nonOrderedPostProcessors集合
			//这个getBean方法实际上已经将bean实例创建出来了
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		//无序排序，直接执行postProcessBeanFactory方法

		//对于nonOrderedPostProcessors集合中的BeanFactoryPostProcessor按照顺序从前向后回调postProcessBeanFactory方法
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		//2.8:清除元数据相关的缓存，后处理器可能己经修改了原始的一些元数据
		beanFactory.clearMetadataCache();
	}

	// * 实例化和注册所有的BeanPostProcessor，如果给出显式顺序，则按照顺序注册。
	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		//1.获取容器beanFactory中，所有的接口BeanPostProcessor:类型的实现类
		// 从beanFactory中获取所有BeanPostProcessor类型的bean定义的名称数组
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		//得到最终容器中bean后处理器的数量：
		//容器中己注册bean后处理器数量+即将要注册的后处理器BeanPostProcessorChecker+容器中还没有注册bean后处理器数量
		//beanProcessor的目标计数器
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		//2.检查出哪些bean没有资格被所有bean后处理器处理，记录相应的日志信息
		//注册一个BeanPostProcessorChecker处理器用
		//会在Bean创建完后检查可在当前Bean上起作用的BeanPostProcessor个数与总的BeanPostProcessor个数，如果起作用的个数少于总数，则输出日志信息。
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		//3.初始化一些集合
		//存放实现了接口PriorityOrdered bean.后处理器BeanPostProcessor
		//这个集合保存实现了PriorityOrdered接口的BeanPostProcessor实例
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		//存放Spring内部的bean后处理器BeanPostProcessor
		//这个集合保存Spring内部的BeanPostProcessor实例
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		//存放实现了接口ordered bean后处理器BeanPostProcessor
		//这个集合保存实现了Ordered接口的BeanPostProcessor的beanName
		List<String> orderedPostProcessorNames = new ArrayList<>();
		//存放无序的bean后处理器BeanPostProcessor
		//这个集合保存普通的BeanPostProcessor的beanName
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		//4.遍历所有后处理器BeanPostProcessor的名称
		for (String ppName : postProcessorNames) {
			/*如果该名称的bean定义还实现了PriorityOrdered接口，那么初始化*/
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				//获取该名称的bean定义的实例，这一步创建了BeanPostProcessor实例
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				//该实例加入到priorityOrderedPostProcessors集合
				priorityOrderedPostProcessors.add(pp);
				//是Spring内部的BeanPostProcessor,和实例化注解注解bean关系密切，如@Autowired,
				//如果该实例还实现了MergedBeanDefinitionPostProcessor接口
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					//该实例加入到internalPostProcessors集合
					internalPostProcessors.add(pp);
				}
			}
			/*否则，如果该名称的bean定义还实现了Ordered接口*/
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				//beanName加入到orderedPostProcessorNames集合
				orderedPostProcessorNames.add(ppName);
			}
			/*否则，如果该名称的bean定义即没有实现PriorityOrdered接口也没有实现Ordered接口*/
			else {
				//beanName加入到nonOrderedPostProcessorNames集合
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		//5.排序并将实现了接口Priorityorderedbean,后处理器，注册到容器beanFactory中
		/*
		 * 对实现了PriorityOrdered接口的BeanPostProcessor实例进行排序，随后按照排序顺序从前向后注册BeanPostProcessor实例
		 */
		//这里对priorityOrderedPostProcessors集合的元素根据Ordered顺序进行排序
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//注册给定的BeanPostProcessor
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		//这个集合保存实现了Ordered接口的BeanPostProcessor实例
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		//遍历orderedPostProcessorNames数组
		for (String ppName : orderedPostProcessorNames) {
			//获取该名称的bean定义的实例，这一步创建了BeanPostProcessor实例
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			//该实例加入到orderedPostProcessors集合
			orderedPostProcessors.add(pp);
			//如果该实例还实现了MergedBeanDefinitionPostProcessor接口
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				//该实例加入到internalPostProcessors集合
				internalPostProcessors.add(pp);
			}
		}
		//6.排序并将实现了接口ordered/bean,后处理器，注册到容器beanFactory中
		/*
		 * 对实现了Ordered接口的BeanPostProcessor实例进行排序，随后按照排序顺序从前向后注册BeanPostProcessor实例
		 */
		//这里对orderedPostProcessors集合的元素根据Ordered顺序进行排序
		sortPostProcessors(orderedPostProcessors, beanFactory);
		//注册给定的BeanPostProcessor
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		//这个集合保存普通的BeanPostProcessor实例
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		//遍历nonOrderedPostProcessorNames数组
		for (String ppName : nonOrderedPostProcessorNames) {
			//获取该名称的bean定义的实例，这一步创建了BeanPostProcessor实例
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			//该实例加入到nonOrderedPostProcessors集合
			nonOrderedPostProcessors.add(pp);
			//如果该实例还实现了MergedBeanDefinitionPostProcessor接口
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				//该实例加入到internalPostProcessors集合
				internalPostProcessors.add(pp);
			}
		}
		//7.将无序普通bean后处理器，注册到容器beanFactory中
		//注册给定的BeanPostProcessor，不需要排序
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		//8.最后，将Spring容器内部的BeanPostProcessor,注册到bean后处理器链的尾部
		/*
		 * 对实现了MergedBeanDefinitionPostProcessor接口的Spring内部的BeanPostProcessor实例进行排序，随后按照排序顺序从前向后注册BeanPostProcessor实例
		 */
		//这里对internalPostProcessors集合的元素根据Ordered顺序进行排序
		sortPostProcessors(internalPostProcessors, beanFactory);
		//注册给定的BeanPostProcessor，相当于内部的BeanPostProcessor会被移动到处理器集合的尾部
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		/*
		 * 重新注册ApplicationListenerDetector，会被移到处理器集合的末尾
		 */
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			((AbstractBeanFactory) beanFactory).addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
