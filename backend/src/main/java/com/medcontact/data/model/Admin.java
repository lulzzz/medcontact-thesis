package com.medcontact.data.model;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import javax.persistence.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Entity
@Table(name="admins")
@DiscriminatorValue("ADMIN")
@Data

/* This annotations prevents the lombok library
 * from calling the superclass' equals and hashCode 
 * methods thus preventing warnings about
 * overriding the mentioned methods. */

@EqualsAndHashCode(callSuper=false)

public class Admin extends BasicUser {
	private static final long serialVersionUID = 820358146018064860L;
	
	public Admin() {
		
		/* This call sets the default values of the
		 * user data. */
		
		super();
		this.role = ApplicationRole.ADMIN;
	}
	
	public static AdminBuilder getBuilder() {
		return new AdminBuilder();
	}
	
	public static class AdminBuilder extends BasicUser.BasicUserBuilder {
		public AdminBuilder() {
			this.user = new Admin();
		}
	}
}
