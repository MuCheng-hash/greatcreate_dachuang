package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

@Data
public class UserAccountRoleAssignRequest {
    private List<Long> roleIds;
    private String dataScope;
}
