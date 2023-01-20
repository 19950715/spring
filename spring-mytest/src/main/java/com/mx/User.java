package com.mx;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

/**
 * @Author pengwen
 * @DESCRIPTION
 * @Create 2022/8/18
 */
public class User {
	private String id;
	private String userName;

	public User() {
	}

	public User(String id, String userName) {
		this.id = id;
		this.userName = userName;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	@Override
	public String toString() {
		return "User{" + "id='" + id + '\'' + ", userName='" + userName + '\'' + '}';
	}
}
