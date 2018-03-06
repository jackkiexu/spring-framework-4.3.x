/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.method.annotation;

import org.springframework.core.MethodParameter;
import org.springframework.ui.Model;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link Model} arguments and handles {@link Model} return values.
 *
 * <p>A {@link Model} return type has a set purpose. Therefore(因此|所以) this handler
 * should be configured ahead of handlers that support any return value type
 * annotated with {@code @ModelAttribute} or {@code @ResponseBody} to ensure 放在解析 @ModelAttribute @ResponseBody 之前
 * they don't take over.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
// HandlerMethodArgumentResolver: 针对 Model及其子类的参数, 数据的获取一般通过 ModelAndViewContainer.getModel()
// HandlerMethodReturnValueHandler: 针对 Model 及其子类的返回值处理器, 主要还是将 ModelAndView 中的 model 设置到 ModelAndViewContainer
public class ModelMethodProcessor implements HandlerMethodArgumentResolver, HandlerMethodReturnValueHandler {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return Model.class.isAssignableFrom(parameter.getParameterType());  //  参数类型是 Model
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
		return mavContainer.getModel(); // 直接通过 ModelAndViewContainer 获取
	}

	@Override
	public boolean supportsReturnType(MethodParameter returnType) { // 参数类型是 Model
		return Model.class.isAssignableFrom(returnType.getParameterType());
	}

	@Override
	public void handleReturnValue(Object returnValue, MethodParameter returnType,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

		if (returnValue == null) { // 若返回值是 null, 则直接返回
			return;
		}
		else if (returnValue instanceof Model) { // 若返回值是 Model, 则将 Model 中的属性全部设置到 ModelAndViewContainer 中
			mavContainer.addAllAttributes(((Model) returnValue).asMap());
		}
		else {
			// should not happen
			throw new UnsupportedOperationException("Unexpected return type: " +
					returnType.getParameterType().getName() + " in method: " + returnType.getMethod());
		}
	}

}
