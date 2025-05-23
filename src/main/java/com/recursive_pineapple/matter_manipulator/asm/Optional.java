package com.recursive_pineapple.matter_manipulator.asm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({
    ElementType.FIELD, ElementType.METHOD
})
public @interface Optional {

    public String[] value();
}
