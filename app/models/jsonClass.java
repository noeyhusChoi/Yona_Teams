/**
 * Yona, 21st Century Project Hosting SW
 * <p>
 * Copyright Yona & Yobi Authors & NAVER Corp. & NAVER LABS Corp.
 * https://yona.io
 **/

package models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.validation.constraints.Size;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

/**
 * A webhook to be sent by events in project
 */
public class jsonClass {

	public String type;
	public ArrayList<Attachment> attachments;

	class Attachment{
		public String contentType;
		public Object contentUrl;
		public Content content;
	}
	
	class Body{
		public String type;
		public String size;
		public String weight;
		public String text;
		public boolean separator;
		public ArrayList<Item> items;
	}
	
	class Column{
		public String type;
		public ArrayList<Item> items;
	}
	
	class Content{
		public String $schema;
		public String type;
		public String version;
		public ArrayList<Body> body;
	}
	
	class Item{
		public String type;
		public ArrayList<Column> columns;
		public String text;
		public boolean isSubtle;
		public boolean wrap;
		public String weight;
		public String spacing;
	}
}


