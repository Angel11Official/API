package com.myapi.myapigateway;

import com.myapiclientsdk.utils.SignUtils;
import com.myapicommon.model.entity.InterfaceInfo;
import com.myapicommon.model.entity.User;
import com.myapicommon.service.InnerInterfaceInfoService;
import com.myapicommon.service.InnerUserInterfaceInfoService;
import com.myapicommon.service.InnerUserService;
import io.swagger.models.auth.In;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 全局过滤器 统一处理业务逻辑
 */
@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1");
    @DubboReference
    private InnerUserService innerUserService;

    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    private static final String INTERFACE_HOST = "http://localhost:8123";
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //1.用户发送请求到API网关
        //2.请求日志
        ServerHttpRequest request = exchange.getRequest();
        String path = INTERFACE_HOST + request.getPath().value();
        String method = request.getMethod().toString();
        log.info("请求唯一标识:" + request.getId());
        log.info("请求路径:" + path);
        log.info("请求方法:" + method);
        log.info("请求参数:" + request.getQueryParams());
        log.info("请求来源地址:" + request.getLocalAddress().getHostString());
        log.info("请求远端地址:" + request.getRemoteAddress());
        String sourceAddress = request.getLocalAddress().getHostString();
        log.info("sourceAddress:" + sourceAddress);
        ServerHttpResponse response = exchange.getResponse();
        //3.黑白名单
        //如果白名单不包含请求来源地址 拒绝掉
        if(!IP_WHITE_LIST.contains(sourceAddress)){
            response.setStatusCode(HttpStatus.FORBIDDEN);//403
            return response.setComplete();
        }
        //4.鉴权
        //获取请求参数
        HttpHeaders headers = request.getHeaders();
        String accessKey = headers.getFirst("accessKey");
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        String body = headers.getFirst("body");
        //todo 实际情况是去数据库中查找是否已经分配给用户
        User invokeUser = null;
        try{
            invokeUser = innerUserService.getInvokeUser(accessKey);
        }catch (Exception e){
            log.error("getInvokeUser error",e);
        }
        if(invokeUser == null){
            return handleNoAuth(response);
        }
        if(Long.parseLong(nonce)>10000L){
            return handleNoAuth(response);
        }
        //时间戳校验
        Long currentTime = System.currentTimeMillis()/1000;
        Long FIVE_MINUTES = 60 * 5L;
        if((currentTime - Long.parseLong(timestamp)) >= FIVE_MINUTES){
            return handleNoAuth(response);
        }
        //用同样的方法来生成签名
        //TODO-实际情况中时从数据库中查找 secretKey
        String secretKey = invokeUser.getSecretKey();
        String serverSign = SignUtils.getSign(body,secretKey);
        if(sign == null || !sign.equals(serverSign)){
            return handleNoAuth(response);
        }


        
        //5.Todo-从数据库中查看要请求的模拟接口是否存在，以及请求方法是否匹配，校验请求参数
        InterfaceInfo interfaceInfo = null;
        try{

            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(path, method);
        }catch (Exception e){
            log.error("getInterfaceInfo error",e);
        }
        if(interfaceInfo == null){
            return handleNoAuth(response);
        }

        //6.TODO 调用之前检查是否有调用次数
        Long invokeInterfaceId = interfaceInfo.getId();
        Long invokeUserId = invokeUser.getId();
        boolean checkResult = true;
        try{
             checkResult = innerUserInterfaceInfoService.checkInvoke(invokeInterfaceId, invokeUserId);
        }catch (Exception e){
            log.error("getInterfaceInfo error",e);
        }
        if(!checkResult){
            return handleNoAuth(response);
        }
        //7.请求转发 + 调用模拟接口 + 响应日志
        return handleResponse(exchange,chain,invokeInterfaceId,invokeUserId);
//        return chain.filter(exchange);
    }

    public Mono<Void>handleResponse(ServerWebExchange exchange, GatewayFilterChain chain,long interfaceInfoId,long userId){
        try {
            ServerHttpResponse originalResponse = exchange.getResponse();
            //缓存数据
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            //拿到响应码
            HttpStatus statusCode = originalResponse.getStatusCode();
            if(statusCode == HttpStatus.OK){
                //增强能力
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                    //增强：转发、调用完接口会调用这个writeWith函数
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 往返回值里写数据
                            // 拼接字符串
                            return super.writeWith(fluxBody.map(dataBuffer -> {
                                //8.TODO-调用成功，接口调用次数+1 invokeCount
                                //记得抛异常
                                try {
                                    innerUserInterfaceInfoService.invoke(interfaceInfoId,userId);
                                } catch (Exception e) {
                                    log.info("invokeCount error",e);
                                }
                                //        if(response.getStatusCode() == HttpStatus.OK){
                                //
                                //        }else{
                                //            //9.调用失败，返回一个规范的错误码
                                //            return handleInvokeError(response);
                                //        }
                                byte[] content = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(content);
                                DataBufferUtils.release(dataBuffer);//释放掉内存
                                // 构建日志
                                StringBuilder sb2 = new StringBuilder(200);
                                sb2.append("<--- {} {} \n");
                                List<Object> rspArgs = new ArrayList<>();
                                rspArgs.add(originalResponse.getStatusCode());
                                //rspArgs.add(requestUrl);
                                String data = new String(content, StandardCharsets.UTF_8);//data
                                sb2.append(data);
                                //打印日志
                                log.info(sb2.toString(), rspArgs.toArray());//log.info("<-- {} {}\n", originalResponse.getStatusCode(), data);
                                log.info("响应结果："+data);
                                return bufferFactory.wrap(content);
                            }));
                        } else {
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                //设置 response对象 为装饰过的response对象
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            return chain.filter(exchange);//降级处理返回数据
        }catch (Exception e){
            log.error("网关处理响应异常.\n" + e);
            return chain.filter(exchange);
        }
    }
    @Override
    public int getOrder() {
        return -1;
    }

    public Mono handleNoAuth (ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN); //403
        return response.setComplete();
    }

    public Mono handleInvokeError (ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR); //500
        return response.setComplete();
    }
}