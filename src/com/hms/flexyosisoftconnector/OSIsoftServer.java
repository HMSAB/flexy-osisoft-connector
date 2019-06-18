package com.hms.flexyosisoftconnector;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.ArrayList;

import com.ewon.ewonitf.EWException;
import com.ewon.ewonitf.ScheduledActionManager;
import com.hms.flexyosisoftconnector.JSON.*;

/**
 *
 * Class object for an OSIsoft PI Server.
 *
 * HMS Industrial Networks Inc. Solution Center
 *
 * @author thk
 *
 */

public class OSIsoftServer {

   // IP address of OSIsoft Server
   private String serverIP;

   // BASE64 encoded BASIC authentication credentials
   private static String authCredentials;

   // WebID for the database being used
   private static String dbWebID;

   // URL for the OSIsoft Server
   private static String targetURL;

   // Post Headers
   private static String postHeaders;

   // Unique name of this flexy
   private static String flexyName; 

   private static boolean connected = true;

   public static final int LINK_ERROR = 32601;
   public static final int SEND_ERROR = 32603;
   public static final int JSON_ERROR = -100;

   static final int NO_ERROR = 0;
   static final int EWON_ERROR = 1;
   static final int AUTH_ERROR = 2;
   static final int WEB_ID_ERROR = 2;
   static final int GENERIC_ERROR = 254;

   static final String AUTH_ERROR_STRING = "Authorization has been denied for this request.";
   static final String WEB_ID_ERROR_STRING = "Unknown or invalid WebID format:";

   private static StringBuilderLite batchBuffer;
   private static final int StringBuilderNumChars = 500000;
   private static int batchCount;

   private static SimpleDateFormat dateFormat;

   public OSIsoftServer(String ip, String login, String webID, String name) {
      serverIP = ip;
      authCredentials = login;
      dbWebID = webID;
      flexyName = name;
      targetURL = "https://" + serverIP + "/piwebapi/";
      postHeaders = "Authorization=Basic " + authCredentials + "&Content-Type=application/json&X-Requested-With=JSONHttpRequest";
      batchBuffer = new StringBuilderLite(StringBuilderNumChars);
      dateFormat = new SimpleDateFormat("yyyy-MM-ddTHH:mm:ss");
   }

   public static int RequestHTTPS(String CnxParam, String Method, String Headers, String TextFields, String FileFields, String FileName) throws JSONException
   {
      int res = NO_ERROR;

      try {
         res = ScheduledActionManager.RequestHttpX(CnxParam, Method, Headers, TextFields, FileFields, FileName);
      } catch (EWException e) {
         Logger.LOG_EXCEPTION(e);
         res = EWON_ERROR;
      }

      if(!FileName.equals("") && res == NO_ERROR)
      {
         JSONTokener JsonT = new JSONTokener(FileReader.readFile("file://" +FileName));
         JSONObject response = new JSONObject(JsonT);
         if(response.has("Message"))
         {
            res = AUTH_ERROR;
            Logger.LOG_ERR("User Credentials are incorrect");
         }
         if(response.has("Errors"))
         {
            JSONArray errors = response.getJSONArray("Errors");
            for( int i = 0; i < errors.length(); i++ )
            {
               String error = errors.getString(i);
               if(error.substring(0, WEB_ID_ERROR_STRING.length()).equals(WEB_ID_ERROR_STRING))
               {
                  res = WEB_ID_ERROR;
                  Logger.LOG_ERR("WEB ID: \"" + dbWebID + "\"");
                  Logger.LOG_ERR("WEB ID Error: Supplied Web ID does not exist on this server");
               } else
               {
                  res = GENERIC_ERROR;
                  Logger.LOG_ERR(error);
               }
            }
         }
      } else if (res == LINK_ERROR)
      {
         if (connected == true)
         {
            Logger.LOG_ERR("Could not connect to OSIsoft Server, link is down");
            connected = false;
         }
      } else if (res == SEND_ERROR)
      {
         if (connected == true)
         {
            Logger.LOG_ERR("Could not connect to OSIsoft Server, server is offine or unreachable");
            connected = false;
         }
      } else if (res != NO_ERROR)
      {
         Logger.LOG_ERR("Sending Failed. Error #" + res );
      }

      if (res == NO_ERROR && connected == false)
      {
         connected = true;
         Logger.LOG_ERR("Connection restored");
      }

      return res;
   }

   // Looks up and sets a tags PI Point web id.  If the tag does not exist
   // in the data server a PI Point is created for that tag.
   public int setTagWebId(Tag tag) throws JSONException {

      int res = NO_ERROR;

      //HTTPS responses are stored in this file
      String responseFilename = "/usr/response.txt";

      String tagName = tag.getTagName() + "-" + flexyName;

      //url for the dataserver's pi points
      String url = "https://" + serverIP +"/piwebapi/dataservers/" + dbWebID + "/points";

      //Check if the tag already exists in the dataserver
      res = RequestHTTPS(url + "?nameFilter=" + tagName, "Get", postHeaders,"", "", responseFilename);
      if (res == NO_ERROR)
      {
         //Parse the JSON response and retrieve the JSON Array of items
         JSONTokener JsonT = new JSONTokener(FileReader.readFile("file://" +responseFilename));
         JSONObject requestResponse = new JSONObject(JsonT);
         JSONArray items = new JSONArray();
         if(requestResponse.has("Items")) items = requestResponse.getJSONArray("Items");

         if (items.length() > 0) {
            //tag exists
            tag.setWebID(items.getJSONObject(0).getString("WebId"));
         } else {
            //tag does not exist and must be created
            res = RequestHTTPS(url, "Post", postHeaders,buildNewPointBody(tagName, tag.getDataType()), "", responseFilename);

            if (res == NO_ERROR)
            {
               //The WebID is sent back in the headers of the previous post
               //however, there is no mechanism currently to retrieve it so
               //another request must be issued.
               res = RequestHTTPS(url + "?nameFilter=" + tagName, "Get", postHeaders,"", "", responseFilename);
               if (res == NO_ERROR)
               {
                  //Parse the JSON response and retrieve the JSON Array of items
                  JsonT = new JSONTokener(FileReader.readFile("file://" +responseFilename));
                  requestResponse = new JSONObject(JsonT);
                  if(requestResponse.has("Items")) items = requestResponse.getJSONArray("Items");
                  if (items.length() > 0) {
                     //tag exists
                     tag.setWebID(items.getJSONObject(0).getString("WebId"));
                     res = RequestHTTPS(targetURL +"points/" + tag.getWebID() + "/attributes/pointsource", "Put", postHeaders, "\"HMS\"", "", "");
                     if (res != NO_ERROR)
                     {
                        Logger.LOG_ERR("Could not set point source of " + tag.getTagName() + ". Error: " + res);
                     }
                  } else {
                     //tag does not exist, error
                     Logger.LOG_ERR("PI Point creation failed.  Error: " + res);
                  }
               }
            }
            else
            {
               Logger.LOG_ERR("Error in creating tag.  Error: " + res);
            }
         }

         //Delete the https response file
         File file = new File(responseFilename);
         if(!file.delete())
         {
            Logger.LOG_ERR("Failed to delete the HTTPS response file");
         }
      }

      return res;
   }

   // Initializes a list of tags
   public int initTags(ArrayList tagList) throws JSONException{
      int retval = NO_ERROR;
      for (int i = 0; i < tagList.size(); i++) {
         int res = setTagWebId((Tag) tagList.get(i));
         if (res != NO_ERROR) return retval = res;
      }
      return retval;
   }

   public static void startBatch()
   {
      batchCount = 0;
      batchBuffer.clearString();
      batchBuffer.append("{\n");
   }

   public static void addPointToBatch(Tag t, DataPoint d)
   {
      batchCount++;

      batchBuffer.append("  \"" + Integer.toString(batchCount) + "\": {\n");
      batchBuffer.append("    \"Method\": \"POST\",\n");
      batchBuffer.append("    \"Resource\": \"" + targetURL + "streams/" + t.getWebID() + "/Value\",\n");
      batchBuffer.append("    \"Content\": \"" + buildBody(d.getValueString(), d.getTimeStamp(), true) + "\",\n");
      batchBuffer.append("    \"Headers\": {\"Authorization\": \"Basic " + authCredentials + "\"" +"}\n");
      batchBuffer.append("  },\n");
   }

   public static void endBatch()
   {
      batchBuffer.append("}");
   }

   public static boolean postBatch()
   {
      int res = NO_ERROR;
      try {
            res = RequestHTTPS(targetURL +"batch/", "Post", postHeaders, batchBuffer.toString(), "", "");
      } catch (JSONException e) {
            Logger.LOG_ERR("Failed to post tags due to malformed JSON response");
            Logger.LOG_EXCEPTION(e);
            res = JSON_ERROR;
      }

      if(res != NO_ERROR)
      {
         return false;
      }
      return true;
   }


   public boolean  postTagsLive(ArrayList tags)
   {
      String body = "{\n";
      for(int tagIndex = 0; tagIndex < tags.size(); tagIndex++)
      {
         body += "  \"" + Integer.toString(tagIndex-1) + "\": {\n";
         body += "    \"Method\": \"POST\",\n";
         body += "    \"Resource\": \"" + targetURL + "streams/" + ((Tag) tags.get(tagIndex)).getWebID() + "/Value\",\n";
         body += "    \"Content\": \"" + buildBody(((Tag) tags.get(tagIndex)).getTagValue(), getCurrentTimeString(), true) + "\",\n";
         body += "    \"Headers\": {\"Authorization\": \"Basic " + authCredentials + "\"" +"}\n";
         if (tagIndex < tags.size())  body += "  },\n";
         else body += "  }\n";
      }
      body += "}";

      int res = NO_ERROR;
      try {
         res = RequestHTTPS(targetURL +"batch/", "Post", postHeaders, body, "", "");
      } catch (JSONException e) {
         Logger.LOG_ERR("Failed to post tags due to malformed JSON response");
         Logger.LOG_EXCEPTION(e);
         res = JSON_ERROR;
      }

      if(res != NO_ERROR)
      {
         return false;
      }
      return true;
   }

   public static boolean postDataPoint(Tag tag, DataPoint dataPoint)
   {
      int res = NO_ERROR;
      try {
         res = RequestHTTPS(targetURL + "streams/" + tag.getWebID() + "/Value", "Post", postHeaders, buildBody(dataPoint.getValueString(), dataPoint.getTimeStamp(), false), "", "");
      } catch (JSONException e) {
         Logger.LOG_ERR("Failed to post value of " + tag.getTagName() + "due to malformed JSON response");
         Logger.LOG_EXCEPTION(e);
      }

      if(res != NO_ERROR)
      {
         Logger.LOG_ERR("Failed to post value of " + tag.getTagName());
         return false;
      }
      return true;
   }

   // Posts a tag value to the OSIsoft server
   public void postTag(Tag tag) {
      int res = NO_ERROR;
      try {
         res = RequestHTTPS(targetURL + "streams/" + tag.getWebID() + "/Value", "Post", postHeaders, buildBody(tag.getTagValue(), getCurrentTimeString(), false), "", "");
      } catch (JSONException e) {
         Logger.LOG_ERR("Failed to post value of " + tag.getTagName() + "due to malformed JSON response");
         Logger.LOG_EXCEPTION(e);
      }

      if(res != NO_ERROR)
      {
         Logger.LOG_ERR("Failed to post value of " + tag.getTagName());
      }
   }

   // Builds and returns the body content
   // Hardcoded JSON payload
   private static String buildBody(String value, String timestamp, boolean escapeQuotes) {
      String quote;
      if(escapeQuotes)
      {
         quote = "\\\"";
      } else
      {
         quote = "\"";
      }

      String jsonBody = "{" + quote + "Timestamp" + quote +": " + quote + timestamp + quote + "," + quote + "Value" + quote + ": " + value
                        + "," + quote + "UnitsAbbreviation" + quote + ": " + quote + quote + "," + quote + "Good" + quote + ": true,"
                        + quote + "Questionable" + quote + ": false" + "}";
      return jsonBody;
   }

   // Creates an OSIsoft compatible timestamp string
   private static String getCurrentTimeString()
   {
      Date d = new Date();
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-ddTHH:mm:ss");
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      String timestamp = dateFormat.format(d);
      return timestamp;
   }

   public String convertTimeString(Date d)
   {
      String timestamp = dateFormat.format(d);
      return timestamp;
   }

   private static String buildNewPointBody(String tagName, byte tagType) {
      String type;

      switch (tagType) {
            case DataPoint.TYPE_BOOLEAN:
               type = "Digital";
               break;
            case DataPoint.TYPE_FLOAT:
               type = "Float64";
               break;
            case DataPoint.TYPE_INT:
               type = "Int32";
               break;
            case DataPoint.TYPE_DWORD:
               type = "Float64";
               break;
            default:
               Logger.LOG_ERR("Invalid datatype of " + tagType + " for new PI Point");
               return "";
         }

         String jsonBody = "{\r\n" +
            "  \"Name\": \""+ tagName +"\",\r\n" +
            "  \"Descriptor\": \""+tagName+"\",\r\n" +
            "  \"PointClass\": \"classic\",\r\n" +
            "  \"PointType\": \"" + type + "\",\r\n" +
            "  \"EngineeringUnits\": \"\",\r\n" +
            "  \"Step\": false,\r\n" +
            "  \"Future\": false\r\n" +
            "}";
      return jsonBody;
   }
}