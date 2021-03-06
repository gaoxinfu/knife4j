/*
 * Copyright (C) 2018 Zhejiang xiaominfo Technology CO.,LTD.
 * All rights reserved.
 * Official Web Site: http://www.xiaominfo.com.
 * Developer Web Site: http://open.xiaominfo.com.
 */

package com.github.xiaoymin.knife4j.spring.web;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSort;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import com.github.xiaoymin.knife4j.annotations.ApiSupport;
import com.github.xiaoymin.knife4j.spring.model.*;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.plugin.core.PluginRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriComponents;
import springfox.documentation.RequestHandler;
import springfox.documentation.annotations.ApiIgnore;
import springfox.documentation.oas.mappers.ServiceModelToOpenApiMapper;
import springfox.documentation.oas.web.WebMvcOpenApiTransformationFilter;
import springfox.documentation.service.Documentation;
import springfox.documentation.service.Tag;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.RequestHandlerProvider;
import springfox.documentation.spring.web.DocumentationCache;
import springfox.documentation.spring.web.WebMvcRequestHandler;
import springfox.documentation.spring.web.json.Json;
import springfox.documentation.spring.web.json.JsonSerializer;
import springfox.documentation.spring.web.plugins.Docket;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

import static com.google.common.collect.FluentIterable.from;
import static org.springframework.util.MimeTypeUtils.APPLICATION_JSON_VALUE;

/**
 * Knife4j????????????
 * @since:knife4j 3.0
 * @author <a href="mailto:xiaoymin@foxmail.com">xiaoymin@foxmail.com</a> 
 * 2020???10???18??? 18:45:28
 */
@Controller
@ApiIgnore
public class Knife4jOpenApi3ExtController {

    /***
     * sort????????????
     */
    public static final String DEFAULT_SORT_URL = "/v3/api-docs-ext";

    private static final String HAL_MEDIA_TYPE = "application/hal+json";
    private static final Logger LOGGER = LoggerFactory.getLogger(Knife4jOpenApi3ExtController.class);
    private final ServiceModelToOpenApiMapper mapper;
    private final DocumentationCache documentationCache;
    private final JsonSerializer jsonSerializer;
    private final String hostNameOverride;
    private final List<RequestHandlerProvider> handlerProviders;
    private final PluginRegistry<WebMvcOpenApiTransformationFilter, DocumentationType> transformations;

    private final MarkdownFiles markdownFiles;
    /***
     * ????????????mappings
     */
    private List<RestHandlerMapping> globalHandlerMappings=new ArrayList<>();

    private final RequestMethod[] globalRequestMethods={RequestMethod.POST,RequestMethod.GET,RequestMethod.PUT,
            RequestMethod.DELETE,RequestMethod.PATCH,RequestMethod.OPTIONS,RequestMethod.HEAD};


    @Autowired
    public Knife4jOpenApi3ExtController(Environment environment,
                                        ServiceModelToOpenApiMapper mapper, DocumentationCache documentationCache, JsonSerializer jsonSerializer, List<RequestHandlerProvider> handlerProviders,
                                        ObjectProvider<MarkdownFiles> markdownFilesObjectProvider,
                                        @Qualifier("webMvcOpenApiTransformationFilterRegistry")
                                                 PluginRegistry<WebMvcOpenApiTransformationFilter, DocumentationType> transformations) {
        this.mapper = mapper;
        this.documentationCache = documentationCache;
        this.jsonSerializer = jsonSerializer;
        this.hostNameOverride = environment.getProperty(
                "springfox.documentation.swagger.v2.host",
                "DEFAULT");
        this.handlerProviders = handlerProviders;
        this.markdownFiles=markdownFilesObjectProvider.getIfAvailable();
        this.transformations=transformations;
    }

    private Function<RequestHandlerProvider, ? extends Iterable<RequestHandler>> handlers() {
        return new RequestHandlerFunction();
    }
    @RequestMapping(value = DEFAULT_SORT_URL,
            method = RequestMethod.GET,
            produces = { APPLICATION_JSON_VALUE, HAL_MEDIA_TYPE })
    @ResponseBody
    public ResponseEntity<Json> apiSorts(@RequestParam(value = "group", required = false) String swaggerGroup,HttpServletRequest request) {
        String groupName = java.util.Optional.ofNullable(swaggerGroup).orElse(Docket.DEFAULT_GROUP_NAME);
        Documentation documentation = documentationCache.documentationByGroup(groupName);
        if (documentation == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        OpenAPI oas = mapper.mapDocumentation(documentation);
        OpenAPIExt openAPIExt=new OpenAPIExt(oas);
        openAPIExt.setSwaggerBootstrapUi(initSwaggerBootstrapUi(request,documentation,openAPIExt));
        // Method ?????????
        return new ResponseEntity<Json>(jsonSerializer.toJson(openAPIExt), HttpStatus.OK);
    }


    private SwaggerBootstrapUi initSwaggerBootstrapUi(HttpServletRequest request, Documentation documentation, OpenAPIExt swaggerExt){
        SwaggerBootstrapUi swaggerBootstrapUi=new SwaggerBootstrapUi();
        WebApplicationContext wc=WebApplicationContextUtils.getWebApplicationContext(request.getServletContext());
        //v1.8.9
        if (wc==null){
            String msg="WebApplicationContext is Empty~!,Enable SwaggerBootstrapUi fun fail~!";
            LOGGER.warn(msg);
            swaggerBootstrapUi.setErrorMsg(msg);
            return swaggerBootstrapUi;
        }
        //??????????????????????????????.
        Iterator<Tag> tags=documentation.getTags().iterator();
        //???1.8.7?????????,??????Spring?????????????????????HandleMappings
        //initGlobalRequestMappingArray(wc,swaggerExt);
        //since 1.9.0 SpringMvc?????????????????????
        initGlobalRequestMappingArray(swaggerExt);
        //1.8.7
        List<SwaggerBootstrapUiTag> targetTagLists=Lists.newArrayList();
        List<SwaggerBootstrapUiPath> targetPathLists=Lists.newArrayList();

        while (tags.hasNext()) {
            Tag sourceTag = tags.next();
            String tagName = sourceTag.getName();
            //??????order???
            int order=Integer.MAX_VALUE;
            SwaggerBootstrapUiTag tag=new SwaggerBootstrapUiTag(order);
            tag.name(tagName).description(sourceTag.getDescription());
            Api tagApi=null;
            RestHandlerMapping tagMapping=null;
            for (RestHandlerMapping rhm:globalHandlerMappings){
                Api api = rhm.getBeanType().getAnnotation(Api.class);
                if (api!=null){
                    //????????????api????????????tags??????
                    if (api.tags()!=null&&api.tags().length>0){
                        if (Lists.newArrayList(api.tags()).contains(tagName)) {
                            tagApi=api;
                            tagMapping=rhm;
                            createPathInstance(rhm,targetPathLists);
                        }else{
                            ///????????????tags????????????""?????????
                            String firstTag=api.tags()[0];
                            if (StringUtils.isEmpty(firstTag)){
                                if (checkExists(tagName,rhm.getBeanType())){
                                    tagApi=api;
                                    tagMapping=rhm;
                                    createPathInstance(rhm,targetPathLists);
                                }
                            }
                        }
                    }else{
                        //??????tags???????????????,??????value?????????
                        //api-1872-controller
                        //??????@Api(value = "187??????",description = "187?????????????????????",position = 297)?????????
                        //???Springfox-Swagger???,??????value???????????????,?????????Tag???????????????Class?????????
                        //??????????????????????????????name??????
                        if (checkExists(tagName,rhm.getBeanType())){
                            if (!StringUtils.isEmpty(api.value())){
                                tag.name(api.value());
                                //tagName=api.value();
                            }
                            tagApi=api;
                            tagMapping=rhm;
                            createPathInstance(rhm,targetPathLists);
                        }
                    }
                }else{
                    if (checkExists(tagName,rhm.getBeanType())){
                        tagMapping=rhm;
                        createPathInstance(rhm,targetPathLists);
                    }
                }
            }
            if (tagMapping!=null){
                tag.setOrder(getRestTagOrder(tagMapping.getBeanType(),tagApi));
                String author=getRestTagAuthor(tagMapping.getBeanType());
                if (author!=null&&!"".equalsIgnoreCase(author)){
                    tag.setAuthor(author);
                }
            }
            targetTagLists.add(tag);
        }
        Collections.sort(targetTagLists, new Knife4jTagComparator());
        Collections.sort(targetPathLists, new Knife4jPathComparator());
        swaggerBootstrapUi.setTagSortLists(targetTagLists);
        swaggerBootstrapUi.setPathSortLists(targetPathLists);
        if (markdownFiles!=null){
            swaggerBootstrapUi.setMarkdownFiles(markdownFiles.getMarkdownFiles());
        }
        return swaggerBootstrapUi;
    }

    /***
     * since 1.9.0
     * @param swaggerExt
     */
    private void initGlobalRequestMappingArray(OpenAPIExt swaggerExt){
        if (globalHandlerMappings.size()==0) {
            //?????????
            String parentPath = "";
            try{
                List<RequestHandler> requestHandlers = from(handlerProviders).transformAndConcat(handlers()).toList();
                for (RequestHandler requestHandler:requestHandlers){
                    if (requestHandler instanceof WebMvcRequestHandler){
                        WebMvcRequestHandler webMvcRequestHandler=(WebMvcRequestHandler)requestHandler;
                        RequestMappingInfo requestMappingInfo= (RequestMappingInfo) webMvcRequestHandler.getRequestMapping().getOriginalInfo();
                        Set<RequestMethod> restMethods=requestMappingInfo.getMethodsCondition().getMethods();
                        Set<String> patterns =requestMappingInfo.getPatternsCondition().getPatterns();
                        HandlerMethod handlerMethod=webMvcRequestHandler.getHandlerMethod();
                        Class<?> controllerClazz=ClassUtils.getUserClass(handlerMethod.getBeanType());
                        Method method = ClassUtils.getMostSpecificMethod(handlerMethod.getMethod(),controllerClazz);
                        for (String url : patterns) {
                            if (LOGGER.isDebugEnabled()){
                                LOGGER.debug("url:"+url+"\r\nclass:"+controllerClazz.toString()+"\r\nmethod:"+method.toString());
                            }
                            globalHandlerMappings.add(new RestHandlerMapping(parentPath+url,controllerClazz,method,restMethods));
                        }
                    }
                }
            }catch (Exception e){
                LOGGER.error(e.getMessage(),e);
            }
        }

    }

    /***
     * 1.8.0~1.8.9?????????????????????
     * @param wc
     * @param swaggerExt
     */
    @Deprecated
    private void initGlobalRequestMappingArray(WebApplicationContext wc,SwaggerExt swaggerExt){
        if (globalHandlerMappings.size()==0){
            //?????????
            String parentPath="";
            //??????basePath
            if (!StringUtils.isEmpty(swaggerExt.getBasePath())&&!"/".equals(swaggerExt.getBasePath())){
                parentPath+=swaggerExt.getBasePath();
            }
            //????????????????????????????????????????????????????????????????????????
            Map<String, HandlerMapping> requestMappings = BeanFactoryUtils.beansOfTypeIncludingAncestors(wc,HandlerMapping.class,true,false);
            if (requestMappings!=null){
                for (HandlerMapping handlerMapping : requestMappings.values()) {
                    if (handlerMapping instanceof RequestMappingHandlerMapping) {
                        RequestMappingHandlerMapping rmhMapping = (RequestMappingHandlerMapping) handlerMapping;
                        Map<RequestMappingInfo, HandlerMethod> handlerMethods = rmhMapping.getHandlerMethods();
                        for (RequestMappingInfo rmi : handlerMethods.keySet()) {
                            PatternsRequestCondition prc = rmi.getPatternsCondition();
                            Set<RequestMethod> restMethods=rmi.getMethodsCondition().getMethods();
                            Set<String> patterns = prc.getPatterns();
                            HandlerMethod handlerMethod = handlerMethods.get(rmi);
                            for (String url : patterns) {
                                Class<?> clazz = ClassUtils.getUserClass(handlerMethod.getBeanType());
                                Method method = ClassUtils.getMostSpecificMethod(handlerMethod.getMethod(),clazz);
                                if (LOGGER.isDebugEnabled()){
                                    LOGGER.debug("url:"+url+"\r\nclass:"+clazz.toString()+"\r\nmethod:"+method.toString());
                                }
                                globalHandlerMappings.add(new RestHandlerMapping(parentPath+url,clazz,method,restMethods));
                            }
                        }
                    }
                }
            }
        }
    }

    /***
     * ???????????????tag????????????targetPathLists?????????
     * @param rhm
     * @param targetPathLists
     */
    private void createPathInstance(RestHandlerMapping rhm,List<SwaggerBootstrapUiPath> targetPathLists){
        //??????method?????????null????????????null???????????????7???????????????
        if (rhm.getRequestMethods()==null||rhm.getRequestMethods().size()==0){
            for (RequestMethod requestMethod:globalRequestMethods){
                targetPathLists.add(new SwaggerBootstrapUiPath(rhm.getUrl(),requestMethod.name().toUpperCase(),getRestMethodOrder(rhm.getBeanOfMethod())));
            }
        }else{
            for (RequestMethod requestMethod:rhm.getRequestMethods()){
                targetPathLists.add(new SwaggerBootstrapUiPath(rhm.getUrl(),requestMethod.name().toUpperCase(),getRestMethodOrder(rhm.getBeanOfMethod())));
            }
        }
    }

    private String getRestTagAuthor(Class<?> aClass){
        if (aClass!=null){
            ApiSupport apiSupport=ClassUtils.getUserClass(aClass).getAnnotation(ApiSupport.class);
            if (apiSupport!=null){
                return apiSupport.author();
            }
        }
        return null;
    }

    /***
     * ??????tag??????
     * @param aClass
     * @param api
     * @return
     */
    private int getRestTagOrder(Class<?> aClass,Api api){
        int order=Integer.MAX_VALUE;
        //?????????ApiSupport>ApiSort>Api
        if (api!=null){
            //????????????api?????????position??????,???????????????0,????????????,????????????apiSort??????,??????????????????,???????????????,?????????apisort??????,?????????:@Api-position>@ApiSort-value
            int post=api.position();
            if (post==0){
                order=findOrder(aClass);
            }else{
                order=post;
            }
        }else{
            order=findOrder(aClass);
        }
        return order;
    }

    private Integer findOrder(Class<?> aClass){
        int order=Integer.MAX_VALUE;
        if (aClass!=null){
            ApiSort annotation = ClassUtils.getUserClass(aClass).getAnnotation(ApiSort.class);
            if (annotation!=null){
                order=annotation.value();
            }else{
                ApiSupport apiSupport=ClassUtils.getUserClass(aClass).getAnnotation(ApiSupport.class);
                if (apiSupport!=null){
                    order=apiSupport.order();
                }
            }
        }
        return order;
    }

    /***
     * ????????????????????????
     * @param target
     * @return
     */
    private int getRestMethodOrder(Method target){
        //???????????????Sort???
        int pathOrder=Integer.MAX_VALUE;
        //??????????????????Swagger???@ApiOperation
        ApiOperation apiOperation=target.getAnnotation(ApiOperation.class);
        if (apiOperation!=null){
            //??????@ApiOperation???position???
            if (apiOperation.position()!=0){
                pathOrder=apiOperation.position();
            }else{
                ApiOperationSort apiOperationSort=target.getAnnotation(ApiOperationSort.class);
                if (apiOperationSort!=null){
                    pathOrder=apiOperationSort.value();
                }
            }
        }else{
            //?????????,??????????????????@ApiOperationSort
            ApiOperationSort apiOperationSort=target.getAnnotation(ApiOperationSort.class);
            if (apiOperationSort!=null){
                pathOrder=apiOperationSort.value();
            }
        }
        return pathOrder;
    }



    private boolean checkExists(String tagName,Class<?> aClass){
        boolean flag=false;
        if (!StringUtils.isEmpty(tagName)){
            String regexStr=tagName.replaceAll("\\-",".*?");
            //???className?????????
            Pattern pattern=Pattern.compile(regexStr,Pattern.CASE_INSENSITIVE);
            if (pattern.matcher(aClass.getSimpleName()).matches()){
                //??????
                flag=true;
            }
        }
        return flag;
    }



    private String hostName(UriComponents uriComponents) {
        if ("DEFAULT".equals(hostNameOverride)) {
            String host = uriComponents.getHost();
            int port = uriComponents.getPort();
            if (port > -1) {
                return String.format("%s:%d", host, port);
            }
            return host;
        }
        return hostNameOverride;
    }

    static class Knife4jTagComparator implements Comparator<SwaggerBootstrapUiTag>{
        @Override
        public int compare(SwaggerBootstrapUiTag o1, SwaggerBootstrapUiTag o2) {
            return o1.getOrder().compareTo(o2.getOrder());
        }
    }

    static class Knife4jPathComparator implements Comparator<SwaggerBootstrapUiPath>{
        @Override
        public int compare(SwaggerBootstrapUiPath o1, SwaggerBootstrapUiPath o2) {
            return o1.getOrder().compareTo(o2.getOrder());
        }
    }

    static class RequestHandlerFunction implements Function<RequestHandlerProvider, Iterable<RequestHandler>>{
        @Override
        public Iterable<RequestHandler> apply(RequestHandlerProvider input) {
            return input.requestHandlers();
        }
    }
}
