package com.miaoshaproject.validator;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Set;
import java.util.function.Consumer;

@Component
public class ValidatorImpl implements InitializingBean {

    private Validator validator;

    // 实现校验方法并返回校验结果
    public ValidatorResult validate(Object bean) {
        final ValidatorResult validatorResult = new ValidatorResult();
        Set<ConstraintViolation<Object>> violationSet = validator.validate(bean);
        if(violationSet.size() > 0) {
            validatorResult.setHasErrors(true);
            violationSet.forEach(violation -> {
                String errMsg = violation.getMessage();
                String propertyName = violation.getPropertyPath().toString();
                validatorResult.getErrorMsgMap().put(propertyName, errMsg);
            });
        }
        return validatorResult;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // 将hibernate validator通过工厂的初始化方式使其实例化
        this.validator = Validation.buildDefaultValidatorFactory().getValidator();
    }
}
