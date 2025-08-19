/*
package com.example.mapservice.dao;

import com.example.mapservice.bean.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

@Component
public class UserDaoService {
    private static List<User> users = new ArrayList<>();

    private static int userCount = 3;

    static {
        users.add(new User(1,"Kenneth",new Date(),"test1","111111-1111111"));
        users.add(new User(2,"Alice",new Date(),"test2","222222-1111111"));
        users.add(new User(3,"Ju",new Date(),"test3","333333-1111111"));
    }

    public List<User> findAll(){
        return users;
    }

    public User save(User user){
        if (user.getId() == null){
            user.setId(++userCount);
        }

        if (user.getJoinDate() == null){
            user.setJoinDate(new Date());
        }
        users.add(user);

        return user;
    }

    public User findOne(int id){
        for (User user : users){
            if (user.getId() == id) {
                return user;
            }
        }
        return null;
    }

    public User deleteByid(int id){
        Iterator<User> iterator = users.iterator();

        while (iterator.hasNext()){
            User user = iterator.next();

            if(user.getId() == id){
                iterator.remove();
                return user;
            }
        }

        return null;
    }
}
*/
