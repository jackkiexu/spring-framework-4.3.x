/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.accept.PathExtensionContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Extends {@link AbstractMessageConverterMethodArgumentResolver} with the ability to handle
 * method return values by writing to the response with {@link HttpMessageConverter}s.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @since 3.1
 */
// 这个类既相比其父类
public abstract class AbstractMessageConverterMethodProcessor extends AbstractMessageConverterMethodArgumentResolver
		implements HandlerMethodReturnValueHandler {

	/* Extensions associated with the built-in message converters */
	private static final Set<String> WHITELISTED_EXTENSIONS = new HashSet<String>(Arrays.asList(
			"txt", "text", "yml", "properties", "csv",
			"json", "xml", "atom", "rss",
			"png", "jpe", "jpeg", "jpg", "gif", "wbmp", "bmp"));

	private static final Set<String> WHITELISTED_MEDIA_BASE_TYPES = new HashSet<String>(
			Arrays.asList("audio", "image", "video"));

	private static final MediaType MEDIA_TYPE_APPLICATION = new MediaType("application");

	private static final UrlPathHelper DECODING_URL_PATH_HELPER = new UrlPathHelper();

	private static final UrlPathHelper RAW_URL_PATH_HELPER = new UrlPathHelper();

	static {
		RAW_URL_PATH_HELPER.setRemoveSemicolonContent(false);
		RAW_URL_PATH_HELPER.setUrlDecode(false);
	}

	// 根据 Header | URI 中的信息获取请求的 MediaType
	private final ContentNegotiationManager contentNegotiationManager;
	// 根据 URI 的扩展名 来获取对应的 MediaType
	private final PathExtensionContentNegotiationStrategy pathStrategy;

	private final Set<String> safeExtensions = new HashSet<String>();


	/**
	 * Constructor with list of converters only.
	 */
	// 基于 HttpMessageConverter 的构造函数
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters) {
		this(converters, null);
	}

	/**
	 * Constructor with list of converters and ContentNegotiationManager.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters,
			ContentNegotiationManager contentNegotiationManager) {
		this(converters, contentNegotiationManager, null);
	}

	/**
	 * Constructor with list of converters and ContentNegotiationManager as well
	 * as request/response body advice instances.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters,
			ContentNegotiationManager manager, List<Object> requestResponseBodyAdvice) {

		super(converters, requestResponseBodyAdvice);
		this.contentNegotiationManager = (manager != null ? manager : new ContentNegotiationManager());
		this.pathStrategy = initPathStrategy(this.contentNegotiationManager);
		// 获取解析器所能支持的 数据扩展类型
		this.safeExtensions.addAll(this.contentNegotiationManager.getAllFileExtensions());
		// 白名单中的 数据扩展类型支持
		this.safeExtensions.addAll(WHITELISTED_EXTENSIONS);
	}

	// 这里像是直接创建 PathExtensionContentNegotiationStrategy
	private static PathExtensionContentNegotiationStrategy initPathStrategy(ContentNegotiationManager manager) {
		Class<PathExtensionContentNegotiationStrategy> clazz = PathExtensionContentNegotiationStrategy.class;
		PathExtensionContentNegotiationStrategy strategy = manager.getStrategy(clazz);
		return (strategy != null ? strategy : new PathExtensionContentNegotiationStrategy());
	}


	/**
	 * Creates a new {@link HttpOutputMessage} from the given {@link NativeWebRequest}.
	 * @param webRequest the web request to create an output message from
	 * @return the output message
	 */
	protected ServletServerHttpResponse createOutputMessage(NativeWebRequest webRequest) {
		HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
		return new ServletServerHttpResponse(response);
	}

	/**
	 * Writes the given return value to the given web request. Delegates to
	 * {@link #writeWithMessageConverters(Object, MethodParameter, ServletServerHttpRequest, ServletServerHttpResponse)}
	 */
	protected <T> void writeWithMessageConverters(T value, MethodParameter returnType, NativeWebRequest webRequest) throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {
		// 获取 封装了的 ServletServerHttpRequest
		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		// 获取 封装了的 ServletServerHttpResponse
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);
		// 根据 MediaType 获取对应的 HttpMessageConverter, 最终写到远端
		writeWithMessageConverters(value, returnType, inputMessage, outputMessage);
	}

	/**
	 * Writes the given return type to the given output message.
	 * @param value the value to write to the output message
	 * @param returnType the type of the value
	 * @param inputMessage the input messages. Used to inspect the {@code Accept} header.
	 * @param outputMessage the output message to write to
	 * @throws IOException thrown in case of I/O errors
	 * @throws HttpMediaTypeNotAcceptableException thrown when the conditions indicated
	 * by the {@code Accept} header on the request cannot be met by the message converters
	 */
	@SuppressWarnings("unchecked")
	protected <T> void writeWithMessageConverters(T value, MethodParameter returnType,
			ServletServerHttpRequest inputMessage, ServletServerHttpResponse outputMessage)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		Object outputValue;							// 返回值内容
		Class<?> valueType;							// 返回值类型
		Type declaredType;

		if (value instanceof CharSequence) {		// 若 value 是 Char
			outputValue = value.toString();			// 设置 outputValue
			valueType = String.class;				// 设置 返回值的类型
			declaredType = String.class;
		}
		else {
			outputValue = value;					// 设置返回值
			valueType = getReturnValueType(outputValue, returnType); // 获取 返回值的类型
			declaredType = getGenericType(returnType); // 返回值类型
		}

		HttpServletRequest request = inputMessage.getServletRequest();
		// 获取 request 对应的 MediaType, 其中涉及 ContentNegotiationManager
		List<MediaType> requestedMediaTypes = getAcceptableMediaTypes(request);
		// 返回 HttpMessageConverter 所支持的 MediaType
		List<MediaType> producibleMediaTypes = getProducibleMediaTypes(request, valueType, declaredType);
		// 若返回值有数据, 但 producibleMediaType 是空
		if (outputValue != null && producibleMediaTypes.isEmpty()) throw new IllegalArgumentException("No converter found for return value of type: " + valueType);

		Set<MediaType> compatibleMediaTypes = new LinkedHashSet<MediaType>();
		// 找出两端都兼容的 MediaType
		for (MediaType requestedType : requestedMediaTypes) {
			// getMostSpecificMediaType 找出 两个 MediaType 中更加适合 producible
			for (MediaType producibleType : producibleMediaTypes) if (requestedType.isCompatibleWith(producibleType)) compatibleMediaTypes.add(getMostSpecificMediaType(requestedType, producibleType));
		}
		if (compatibleMediaTypes.isEmpty()) {
			// 未出现两者都兼容的 MediaType, 则直接抛出异常
			if (outputValue != null) throw new HttpMediaTypeNotAcceptableException(producibleMediaTypes);
			return;
		}

		List<MediaType> mediaTypes = new ArrayList<MediaType>(compatibleMediaTypes);
		// 将所有的 MediaType 进行排序
		MediaType.sortBySpecificityAndQuality(mediaTypes);

		MediaType selectedMediaType = null;
		// 筛选出其中一个 mediaType
		for (MediaType mediaType : mediaTypes) {
			if (mediaType.isConcrete()) {
				selectedMediaType = mediaType;
				break;
			}
			else if (mediaType.equals(MediaType.ALL) || mediaType.equals(MEDIA_TYPE_APPLICATION)) {
				selectedMediaType = MediaType.APPLICATION_OCTET_STREAM; // 设置 application/octet-stream
				break;
			}
		}
		if (selectedMediaType != null) {
			selectedMediaType = selectedMediaType.removeQualityValue();
			// 循环遍历 HttpMessageConverter, 进行返回值的转换处理 下面进行选择 converter 时, 加入 MediaType 作为考虑
			for (HttpMessageConverter<?> messageConverter : this.messageConverters) {
				// 下面分类 HttpMessageConverter 进行分开处理 <-- 先是支持 Type 的 HttpMessageConverter
				if (messageConverter instanceof GenericHttpMessageConverter) {
					if (((GenericHttpMessageConverter) messageConverter).canWrite(declaredType, valueType, selectedMediaType)) {
						// 通过 ResponseBodyAdvice 在将数据写入输出流之前进行一些增强操作
						outputValue = (T) getAdvice().beforeBodyWrite(outputValue, returnType, selectedMediaType, (Class<? extends HttpMessageConverter<?>>) messageConverter.getClass(), inputMessage, outputMessage);
						if (outputValue != null) {
							// 对 Http 请求中的 header 进行一些处理
							addContentDispositionHeader(inputMessage, outputMessage);
							// HttpMessageConverter 进行处理, 将数据写入 outputStream, 并且写到远端
							((GenericHttpMessageConverter) messageConverter).write(outputValue, declaredType, selectedMediaType, outputMessage);
							logger.info("Written [" + outputValue + "] as \"" + selectedMediaType + "\" using [" + messageConverter + "]");
						}
						return;
					}
				}
				// 普通 HttpMessageConverter 处理
				else if (messageConverter.canWrite(valueType, selectedMediaType)) {
					// 通过 ResponseBodyAdvice 在将数据写入输出流之前进行一些增强操作
					outputValue = (T) getAdvice().beforeBodyWrite(outputValue, returnType, selectedMediaType, (Class<? extends HttpMessageConverter<?>>) messageConverter.getClass(), inputMessage, outputMessage);
					if (outputValue != null) {
						// 对 Http 请求中的 header 进行一些处理
						addContentDispositionHeader(inputMessage, outputMessage);
						// HttpMessageConverter 进行处理, 将数据写入 outputStream, 并且写到远端
						((HttpMessageConverter) messageConverter).write(outputValue, selectedMediaType, outputMessage);
						logger.info("Written [" + outputValue + "] as \"" + selectedMediaType + "\" using [" + messageConverter + "]");
					}
					return;
				}
			}
		}
		if (outputValue != null) throw new HttpMediaTypeNotAcceptableException(this.allSupportedMediaTypes);
	}

	/**
	 * Return the type of the value to be written to the response. Typically this is
	 * a simple check via getClass on the value but if the value is null, then the
	 * return type needs to be examined possibly including generic type determination
	 * (e.g. {@code ResponseEntity<T>}).
	 */
	// 获取 返回值的类型
	protected Class<?> getReturnValueType(Object value, MethodParameter returnType) {
		return (value != null ? value.getClass() : returnType.getParameterType());
	}

	/**
	 * Return the generic type of the {@code returnType} (or of the nested type
	 * if it is an {@link HttpEntity}).
	 */
	private Type getGenericType(MethodParameter returnType) {
		if (HttpEntity.class.isAssignableFrom(returnType.getParameterType())) {
			return ResolvableType.forType(returnType.getGenericParameterType()).getGeneric(0).getType();
		} else {
			return returnType.getGenericParameterType();
		}
	}

	/** // 获取 HttpMessageConverter 锁支持的所有 MediaType
	 * @see #getProducibleMediaTypes(HttpServletRequest, Class, Type)
	 */
	@SuppressWarnings({"unchecked", "unused"})
	protected List<MediaType> getProducibleMediaTypes(HttpServletRequest request, Class<?> valueClass) {
		return getProducibleMediaTypes(request, valueClass, null);
	}

	/**
	 * Returns the media types that can be produced:
	 * <ul>
	 * <li>The producible media types specified in the request mappings, or
	 * <li>Media types of configured converters that can write the specific return value, or
	 * <li>{@link MediaType#ALL}
	 * </ul>
	 * @since 4.2
	 */
	@SuppressWarnings("unchecked") // 获取 HttpMessageConverter 支持的 MediaType
	protected List<MediaType> getProducibleMediaTypes(HttpServletRequest request, Class<?> valueClass, Type declaredType) {
		Set<MediaType> mediaTypes = (Set<MediaType>) request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
		if (!CollectionUtils.isEmpty(mediaTypes)) {
			return new ArrayList<MediaType>(mediaTypes);
		}
		else if (!this.allSupportedMediaTypes.isEmpty()) {
			List<MediaType> result = new ArrayList<MediaType>();
			for (HttpMessageConverter<?> converter : this.messageConverters) { // 循环遍历 HttpMessageConverter 获得支持的 MediaType
				if (converter instanceof GenericHttpMessageConverter && declaredType != null) {
					if (((GenericHttpMessageConverter<?>) converter).canWrite(declaredType, valueClass, null)) { // 若 HttpMessageConverter 支持, 则获取其支持的 MediaType
						result.addAll(converter.getSupportedMediaTypes());
					}
				}
				else if (converter.canWrite(valueClass, null)) {
					result.addAll(converter.getSupportedMediaTypes());	// 若 converter 支持的话, 则将 converter 支持的 MediaType 加入到 result 里面
				}
			}
			return result;
		}
		else {
			return Collections.singletonList(MediaType.ALL);
		}
	}
	// 获取 HttpServletRequest 中获取 MediaType
	private List<MediaType> getAcceptableMediaTypes(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
		List<MediaType> mediaTypes = this.contentNegotiationManager.resolveMediaTypes(new ServletWebRequest(request)); // 通过 Request 获取 MediaType
		return (mediaTypes.isEmpty() ? Collections.singletonList(MediaType.ALL) : mediaTypes);
	}

	/**
	 * Return the more specific of the acceptable and the producible media types
	 * with the q-value of the former.
	 */
	// 通过比较器获取更 可接受|产出的 MediaType
	private MediaType getMostSpecificMediaType(MediaType acceptType, MediaType produceType) {
		MediaType produceTypeToUse = produceType.copyQualityValue(acceptType);
		// 通过 MediaType 比较器进行比较
		return (MediaType.SPECIFICITY_COMPARATOR.compare(acceptType, produceTypeToUse) <= 0 ? acceptType : produceTypeToUse);
	}

	/**
	 * Check if the path has a file extension and whether the extension is
	 * either {@link #WHITELISTED_EXTENSIONS whitelisted} or explicitly
	 * {@link ContentNegotiationManager#getAllFileExtensions() registered}.
	 * If not, and the status is in the 2xx range, a 'Content-Disposition'
	 * header with a safe attachment file name ("f.txt") is added to prevent
	 * RFD exploits.
	 */			// 增加 Disposition 配置 信息到 Header 中
	private void addContentDispositionHeader(ServletServerHttpRequest request, ServletServerHttpResponse response) {
		HttpHeaders headers = response.getHeaders();
		if (headers.containsKey(HttpHeaders.CONTENT_DISPOSITION)) return;
		try {
			int status = response.getServletResponse().getStatus();
			if (status < 200 || status > 299) return;
		} catch (Throwable ex) {
			// ignore
		}

		HttpServletRequest servletRequest = request.getServletRequest();
		String requestUri = RAW_URL_PATH_HELPER.getOriginatingRequestUri(servletRequest);

		int index = requestUri.lastIndexOf('/') + 1;
		String filename = requestUri.substring(index);
		String pathParams = "";

		index = filename.indexOf(';');
		if (index != -1) {
			pathParams = filename.substring(index);
			filename = filename.substring(0, index);
		}

		filename = DECODING_URL_PATH_HELPER.decodeRequestString(servletRequest, filename);
		String ext = StringUtils.getFilenameExtension(filename);

		pathParams = DECODING_URL_PATH_HELPER.decodeRequestString(servletRequest, pathParams);
		String extInPathParams = StringUtils.getFilenameExtension(pathParams);

		// 不是安全的扩展机制
		if (!safeExtension(servletRequest, ext) || !safeExtension(servletRequest, extInPathParams)) {
			headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=f.txt");
		}
	}

	@SuppressWarnings("unchecked")
	private boolean safeExtension(HttpServletRequest request, String extension) {
		if (!StringUtils.hasText(extension)) {
			return true;
		}
		extension = extension.toLowerCase(Locale.ENGLISH);
		if (this.safeExtensions.contains(extension)) {  // 是否是 safe 类型的扩展
			return true;
		}
		String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (pattern != null && pattern.endsWith("." + extension)) {
			return true;
		}
		if (extension.equals("html")) {
			String name = HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE;
			Set<MediaType> mediaTypes = (Set<MediaType>) request.getAttribute(name);
			if (!CollectionUtils.isEmpty(mediaTypes) && mediaTypes.contains(MediaType.TEXT_HTML)) {
				return true;
			}
		}
		return safeMediaTypesForExtension(extension);
	}

	private boolean safeMediaTypesForExtension(String extension) {
		List<MediaType> mediaTypes = null;
		try {
			mediaTypes = this.pathStrategy.resolveMediaTypeKey(null, extension);
		}
		catch (HttpMediaTypeNotAcceptableException ex) {
			// Ignore
		}
		if (CollectionUtils.isEmpty(mediaTypes)) {
			return false;
		}
		for (MediaType mediaType : mediaTypes) {
			if (!safeMediaType(mediaType)) {
				return false;
			}
		}
		return true;
	}

	private boolean safeMediaType(MediaType mediaType) {
		return (WHITELISTED_MEDIA_BASE_TYPES.contains(mediaType.getType()) ||
				mediaType.getSubtype().endsWith("+xml"));
	}

}
