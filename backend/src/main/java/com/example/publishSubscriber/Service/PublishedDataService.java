package com.example.publishSubscriber.Service;

import com.example.publishSubscriber.Entity.ApiLog;
import com.example.publishSubscriber.Entity.ApiLogSendSector;
import com.example.publishSubscriber.Entity.MappedDataForSubscriber;
import com.example.publishSubscriber.Entity.PublishSectorOffset;

import com.example.publishSubscriber.Entity.PublishedData;
import com.example.publishSubscriber.Entity.SubscriberDataUpdateRequest;
import com.example.publishSubscriber.Model.PublishDataApiBrokerRequest;
import com.example.publishSubscriber.Repository.PublishMasterRepository;
import com.example.publishSubscriber.Repository.PublishedDataRepository;
import com.example.publishSubscriber.Repository.ApiLogRepository;
import com.example.publishSubscriber.Repository.ApiLogSendSectorRepository;
import com.example.publishSubscriber.Repository.MappedDataForSubscriberRepository;

import java.util.Map;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.function.Function;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;


@Service
public class PublishedDataService {

    private final GlobalService globalService;

    @Autowired
    private PublishedDataRepository publishedDataRepository;

    @Autowired
    private MappedDataForSubscriberRepository mappedDataForSubscriberRepository;

    @Autowired
    private PublishMasterRepository publishMasterRepository;

    @Autowired
    private ApiLogRepository apiLogRepository;


    @Autowired
    private ApiLogSendSectorRepository apiLogSendSectorRepository;

    public PublishedDataService(GlobalService globalService) {
        this.globalService = globalService;
    }

    public String getGlobalIpAddress() {
        return globalService.getGlobalIpAddress();
    }

    // private static final ObjectMapper objectMapper = new ObjectMapper();

    // static {
    //     // Configure the ObjectMapper to include the JSR-310 module
    //     objectMapper.registerModule(new JavaTimeModule());
    // }


    public PublishedData insertPublishedData(PublishedData data) {
        // Check if publishMasterId exists in publishMaster table
        if (publishMasterRepository.existsById(data.getPublishMasterId())) {
            // Insert data into publishedData table
            return publishedDataRepository.save(data);
        } else {
            // Handle the case where publishMasterId does not exist in publishMaster
            throw new RuntimeException("publishMasterId not found in publishMaster table");
        }
    }

    public List<PublishedData> fetchDataByPublishMasterIds(List<String> publishMasterIds) {
        return publishedDataRepository.findByPublishMasterIdIn(publishMasterIds);
    }

  public String sendPublishDataToBrokerAndLog(PublishedData insertedData) {
    try {
        // Extract the required parameters
        String publishSector = insertedData.getPublishSector();
        String publishMessage = insertedData.getPublishMessage();

        // Create an ExternalApiRequest object with the extracted parameters
        PublishDataApiBrokerRequest externalApiRequest = new PublishDataApiBrokerRequest(publishSector, publishMessage);

        // Print the content for testing
        // String logMessage = "External API Request: " + externalApiRequest;
        // System.out.println(logMessage);

        // Make an HTTP POST request to an external API
        // String externalApiUrl = "http://localhost:8080/api/v1/publisher/sendPublishDataToBrokerEndpoint";  // Replace with your external API URL

        String brokerExternalApiUrl = getGlobalIpAddress(); 

        String externalApiUrl = "http://" + brokerExternalApiUrl + "/publishData"; 

        System.out.println("BROKER IP ADDRESS -  "+externalApiUrl);

        System.out.println(externalApiRequest);

        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Create the request entity with the ExternalApiRequest as the request body
        HttpEntity<PublishDataApiBrokerRequest> requestEntity = new HttpEntity<>(externalApiRequest, headers);

        // Create a RestTemplate
        RestTemplate restTemplate = new RestTemplate();

        // Make the POST request
        ResponseEntity<String> responseEntity = restTemplate.exchange(
                externalApiUrl,
                HttpMethod.POST,
                requestEntity,
                String.class);

        String logMessage;

        // Check the response status
        if (responseEntity.getStatusCode() == HttpStatus.OK) {
            logMessage = "External API call successful. Response: " + responseEntity.getBody();
        } else {
            logMessage = "External API call failed. Status code: " + responseEntity.getStatusCode();
        }

        // Save the log to MongoDB
        String apiName = "sendPublishDataToBroker";
        ApiLog apiLog = new ApiLog(requestEntity, responseEntity.getBody(),apiName, System.currentTimeMillis());
        apiLogRepository.save(apiLog);

        return logMessage;
    } catch (Exception e) {
        return "Error processing external API request: " + e.getMessage();
    }
}


public String fetchAndStoreMappedDataForSubscriber(SubscriberDataUpdateRequest updateRequest) {
    // ...

    String email = updateRequest.getSubscriberUsername();

    // Find the index of the "@" symbol
    int atIndex = email.indexOf('@');
    String username = email.substring(0, atIndex);

    System.out.println(username);

    // ...

    List<PublishedData> unpublishedDataList = publishedDataRepository.findByUsernameAndFetchedForBrokerFalse(
            username,
            updateRequest.getPublishSectorIds()
    );

    List<MappedDataForSubscriber> mappedDataList = new ArrayList<>();

    for (PublishedData data : unpublishedDataList) {
        int latestOffset = findLatestOffsetForUsernameAndPublishMasterId(username, data.getPublishMasterId());

        // Create a MappedDataForSubscriber object and set the offset and timestamp
        MappedDataForSubscriber mappedData = new MappedDataForSubscriber(
                data.getPublishMasterId(),
                data.getPublishSector(),
                username
        );
        mappedData.setOffset(latestOffset + 1);
        mappedData.setTimestamp(LocalDateTime.now());

        // Save each mappedData individually
        MappedDataForSubscriber savedMappedData = mappedDataForSubscriberRepository.save(mappedData);

        // Add the saved mappedData to the list
        mappedDataList.add(savedMappedData);

    

        if("column1".equals(username)){
              // Update the PublishedData to mark it as fetched for broker
            data.setColumn1(true);
            publishedDataRepository.save(data);
        }else{
            data.setColumn2(true);
            publishedDataRepository.save(data);
        }
        
        // // Update the PublishedData to mark it as fetched for broker
        // data.setFetchedForBroker(true);
        // publishedDataRepository.save(data);

        // Store or process the data as needed
        // You can save the original PublishedData back to the repository or perform other operations
        // publishedDataRepository.save(data);
    }

    // Use the list to fetch the latest records for each unique publishMasterId
    List<PublishSectorOffset> latestRecords = fetchLatestRecordsForPublishMasterIds(username, mappedDataList);

    String apiResponseFrom = sendSectorNameAndOffsetToBrokerAndLog(latestRecords);

    // Save the mappedDataList to the database
    mappedDataForSubscriberRepository.saveAll(mappedDataList);

    return apiResponseFrom;
}


private int findLatestOffsetForUsernameAndPublishMasterId(String username, String publishMasterId) {
    MappedDataForSubscriber latestData = mappedDataForSubscriberRepository.findFirstByUsernameAndPublishMasterIdOrderByTimestampDesc(username, publishMasterId);

    if (latestData != null) {
        return latestData.getOffset();
    } else {
        return 0;
    }
}




public List<PublishSectorOffset> fetchLatestRecordsForPublishMasterIds(String username, List<MappedDataForSubscriber> mappedDataList) {
    // Fetch the records with the modified offset for each unique publishMasterId
    List<PublishSectorOffset> latestRecords = mappedDataList
            .stream()
            .filter(mappedData -> mappedData.getOffset() != -1 && username.equals(mappedData.getUsername()))
            // Exclude records with offset -1 and only include records with the specified username
            .collect(Collectors.toMap(
                    MappedDataForSubscriber::getPublishMasterId,
                    Function.identity(),
                    // Merge function to keep the record with the minimum offset
                    (existing, replacement) -> existing.getOffset() <= replacement.getOffset() ? existing : replacement
            ))
            .values()
            .stream()
            .map(mappedData -> {
                // Decrement the offset by 1 (except when the offset is already 0)
                int modifiedOffset = Math.max(mappedData.getOffset() - 1, 0);
                return new PublishSectorOffset(mappedData.getPublishSector(), modifiedOffset);
            })
            .collect(Collectors.toList());

    return latestRecords;
}


 public String sendSectorNameAndOffsetToBrokerAndLog(List<PublishSectorOffset> latestRecords) {
    try {
        // // Extract the required parameters
        // String publishSector = insertedData.getPublishSector();
        // String publishMessage = insertedData.getPublishMessage();

        // // Create an ExternalApiRequest object with the extracted parameters
        // PublishDataApiBrokerRequest externalApiRequest = new PublishDataApiBrokerRequest(publishSector, publishMessage);


        // ObjectMapper objectMapper = new ObjectMapper();
        // String recordsJson = objectMapper.writeValueAsString(latestRecords);

        // // Print the content for testing
        // String logMessage = "External API Request: " + recordsJson;
        // System.out.println(logMessage);

        // // Print the content for testing
        // String logMessage = "External API Request: " + latestRecords;
        // System.out.println(logMessage);

        // Make an HTTP POST request to an external API
        // String externalApiUrl = "http://localhost:8080/api/v1/publisher/sendSectorNameAndOffsetToBrokerEndpoint";  // Replace with your external API URL

        // String externalApiUrl = "http://3.22.75.130:3000/fetchPublishedData";  // Replace with your external API URL

        String brokerExternalApiUrl = getGlobalIpAddress(); 

        String externalApiUrl = "http://" + brokerExternalApiUrl + "/fetchPublishedData"; 

        System.out.println("BROKER IP ADDRESS -  "+externalApiUrl);

        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Create the request entity with the ExternalApiRequest as the request body
        HttpEntity<List<PublishSectorOffset>> requestEntity = new HttpEntity<>(latestRecords, headers);

        System.out.println("my req----");
        System.out.println(requestEntity);

        // Create a RestTemplate
        RestTemplate restTemplate = new RestTemplate();

        // Make the POST request
        ResponseEntity<String> responseEntity = restTemplate.exchange(
                externalApiUrl,
                HttpMethod.POST,
                requestEntity,
                String.class);

        String apiResponse;

        // Check the response status
        if (responseEntity.getStatusCode() == HttpStatus.OK) {
             apiResponse = responseEntity.getBody();
            //  apiResponse = "[\r\n" + //
            //          "    {\r\n" + //
            //          "        \"publishSector\": \"Technology\",\r\n" + //
            //          "        \"messages\": [\r\n" + //
            //          "            \"message 4\",\r\n" + //
            //          "            \"message 5\",\r\n" + //
            //          "            \"message 6\"\r\n" + //
            //          "        ]\r\n" + //
            //          "    },\r\n" + //
            //          "    {\r\n" + //
            //          "        \"publishSector\": \"Pharmaceuticals\",\r\n" + //
            //          "        \"messages\": [\r\n" + //
            //          "            \"message 2\",\r\n" + //
            //          "            \"message 3\"\r\n" + //
            //          "        ]\r\n" + //
            //          "    }\r\n" + //
            //          "]";
        } else {
             apiResponse = "Failed - " + responseEntity.getStatusCode();
        }

        // apiResponse = "Failed - " + responseEntity.getStatusCode();


        // Save the log to MongoDB
        String apiName = "sendSectorNameAndOffsetToBroker";
        ApiLogSendSector apiLog = new ApiLogSendSector(requestEntity, responseEntity.getBody(),apiName, System.currentTimeMillis());
        apiLogSendSectorRepository.save(apiLog);

        return apiResponse;
    } catch (Exception e) {
        return "Error processing external API request: " + e.getMessage();
    }
}


}
