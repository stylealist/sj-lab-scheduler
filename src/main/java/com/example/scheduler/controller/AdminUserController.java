/*
package com.example.mapservice.controller;

import com.example.mapservice.bean.AdminUser;
import com.example.mapservice.bean.AdminUserV2;
import com.example.mapservice.bean.User;
import com.example.mapservice.dao.UserDaoService;
import com.example.mapservice.exception.UserNotFoundException;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.databind.util.BeanUtil;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@RestController()
@RequestMapping("/admin")
public class AdminUserController {
    private UserDaoService userDaoService;

    public AdminUserController(UserDaoService userDaoService) {
        this.userDaoService = userDaoService;
    }

    @GetMapping("/users")
    public MappingJacksonValue retrieveAllUsersAdmin() {
        List<User> users = userDaoService.findAll();

        List<AdminUser> adminUsers = new ArrayList<>();
        AdminUser adminUser = null;
        for(User user : users){
            adminUser = new AdminUser();
            BeanUtils.copyProperties(user, adminUser);

            adminUsers.add(adminUser);
        }

        SimpleBeanPropertyFilter filter = SimpleBeanPropertyFilter.filterOutAllExcept("id","name","joinDate","ssn");
        FilterProvider filterProvider = new SimpleFilterProvider().addFilter("UserInfo",filter);

        MappingJacksonValue mapping = new MappingJacksonValue(adminUsers);
        mapping.setFilters(filterProvider);

        return mapping;
    }
    // --> /admin/v1/users/{id}
    //@GetMapping(value = "/v1/users/{id}")
    //@GetMapping(value = "/users/{id}", headers = "X-API-VERSION=1")
    //@GetMapping(value = "/users/{id}", produces = "application/vnd.company.appv1+json")
    @GetMapping(value = "/users/{id}", params = "version=1")
    public MappingJacksonValue retrieveUserAdmin(@PathVariable int id) {
        User user = userDaoService.findOne(id);

        AdminUser adminUser = new AdminUser();

        if(user == null){
            throw new UserNotFoundException(String.format("ID[%s] not found", id));
        } else {
            BeanUtils.copyProperties(user, adminUser);
        }

        SimpleBeanPropertyFilter filter = SimpleBeanPropertyFilter.filterOutAllExcept("id","name","joinDate","ssn");
        FilterProvider filterProvider = new SimpleFilterProvider().addFilter("UserInfo",filter);

        MappingJacksonValue mapping = new MappingJacksonValue(adminUser);
        mapping.setFilters(filterProvider);

        return mapping;
    }
    // --> /admin/v2/users/{id}
    //@GetMapping(value = "/v2/users/{id}")
    //@GetMapping(value = "/users/{id}", headers = "X-API-VERSION=2")
    //@GetMapping(value = "/users/{id}", produces = "application/vnd.company.appv2+json")
    @GetMapping(value = "/users/{id}", params = "version=2")
    public MappingJacksonValue retrieveUserAdminV2(@PathVariable int id) {
        User user = userDaoService.findOne(id);

        AdminUserV2 adminUser = new AdminUserV2();

        if(user == null){
            throw new UserNotFoundException(String.format("ID[%s] not found", id));
        } else {
            BeanUtils.copyProperties(user, adminUser);
            adminUser.setGrade("VIP"); // grade
        }

        SimpleBeanPropertyFilter filter = SimpleBeanPropertyFilter.filterOutAllExcept("id","name","joinDate","grade");
        FilterProvider filterProvider = new SimpleFilterProvider().addFilter("UserInfoV2",filter);

        MappingJacksonValue mapping = new MappingJacksonValue(adminUser);
        mapping.setFilters(filterProvider);

        return mapping;
    }

}
*/
