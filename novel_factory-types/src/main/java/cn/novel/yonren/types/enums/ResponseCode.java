package cn.novel.yonren.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NULL_EXCEPTION("0003","这个参数不能为空"),
    PARAM_REPETITION("0004","重复参数"),
    ;

    private String code;
    private String info;

}
