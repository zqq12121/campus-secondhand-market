package com.campustrade.module.admin;

import com.campustrade.entity.OperationLog;
import com.campustrade.mapper.OperationLogMapper;
import com.campustrade.security.CurrentUser;
import com.campustrade.security.LoginUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OpLogAspect {

    private static final int DETAIL_MAX = 1000;

    private final OperationLogMapper mapper;
    private final ObjectMapper objectMapper;

    @AfterReturning("@annotation(opLog)")
    public void record(JoinPoint jp, OpLog opLog) {
        LoginUser admin = CurrentUser.get();
        if (admin == null) {
            return;
        }
        Object[] args = jp.getArgs();
        Annotation[][] annotations = ((MethodSignature) jp.getSignature()).getMethod().getParameterAnnotations();
        Long targetId = null;
        List<Object> detailArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            boolean isPath = false;
            for (Annotation a : annotations[i]) {
                isPath |= a instanceof PathVariable;
            }
            if (isPath && targetId == null && args[i] instanceof Number n) {
                targetId = n.longValue();
            } else if (!(args[i] instanceof HttpServletRequest)) {
                detailArgs.add(args[i]);
            }
        }
        OperationLog log = new OperationLog();
        log.setAdminId(admin.id());
        log.setModule(opLog.module());
        log.setAction(opLog.action());
        log.setTargetId(targetId);
        log.setDetail(toJson(detailArgs));
        log.setIp(clientIp());
        mapper.insert(log);
    }

    private String toJson(List<Object> args) {
        try {
            String s = objectMapper.writeValueAsString(args);
            return s.length() > DETAIL_MAX ? s.substring(0, DETAIL_MAX) : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null;
    }
}
