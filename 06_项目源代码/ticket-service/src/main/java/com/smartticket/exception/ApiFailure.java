package com.smartticket.exception;

public class ApiFailure extends RuntimeException {
    private final int status, code;

    public ApiFailure(int status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public int code() {
        return code;
    }

    public static ApiFailure conflict() {
        return new ApiFailure(409, 10011, "工单或任务已变化，请重新查询");
    }

    public static ApiFailure forbidden() {
        return new ApiFailure(403, 40300, "无权执行此操作");
    }
}
