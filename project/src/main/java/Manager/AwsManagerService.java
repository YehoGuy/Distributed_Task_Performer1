package Manager;

import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

public class AwsManagerService {
    private final S3Client s3;
    private final SqsClient sqs;
    private final Ec2Client ec2;
    private String[] workerInstanceIds;

    public static final String ami = "ami-00e95a9222311e8ed";

    public static final Region region1 = Region.US_WEST_2;
    public static final Region region2 = Region.US_EAST_1;

    private static final String BUCKET_NAME = "guyss3bucketfordistributedsystems";
    private static final String LOCAL_MANAGER_Q = "LocalManagerQueue.fifo";
    private static final String LOCAL_MANAGER_MESSAGE_GROUP_ID = "LocaToManagerGroup";
    private static final String MANAGER_LOCAL_Q = "ManagerLocalQueue.fifo";
    private static final String MANAGER_LOCAL_MESSAGE_GROUP_ID = "ManagerToLocalGroup";
    private static final String MANAGER_WORKERS_Q = "ManagerWorkersQueue.fifo";
    private static final String MANAGER_WORKERS_MESSAGE_GROUP_ID = "ManagerToWorkersGroup";
    private static final String WORKERS_MANAGER_Q = "WorkersManagerQueue.fifo";
    private static final String WORKERS_MANAGER_MESSAGE_GROUP_ID = "WorkersToManagerGroup"; 

    private static final String WORKER_PROGRAM_S3KEY = "WorkerProgram.jar";
 


    private static final AwsManagerService instance = new AwsManagerService();

    private AwsManagerService() {
        s3 = S3Client.builder().region(region1).build();
        sqs = SqsClient.builder().region(region1).build();
        ec2 = Ec2Client.builder().region(region2).build();
        workerInstanceIds = new String[9];
    }

    public static AwsManagerService getInstance() {
        if(instance.init()){
            return instance;
        } else{
            return null;
        }
    }

    private boolean init(){
        
        return true;
    }

    //---------------------- S3 Operations -------------------------------------------

    /**
     * Uploads a file to the specified S3 bucket.
     *
     * @param filePath Path of the file to upload.
     * @param filename Name of the file in the S3 bucket.
     * @return The key (path) of the uploaded file in the S3 bucket.
     */
    public String uploadFileToS3(String filePath, String filename) throws Exception {
        String s3Key = filename;
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(s3Key)
                    .build();

            PutObjectResponse response = s3.putObject(putObjectRequest, Paths.get(filePath));
            System.out.println("[DEBUG] File uploaded to S3: " + s3Key);
        } catch (S3Exception e) {
            throw new Exception("[ERROR] " + e.getMessage());
        }
        return s3Key;
    }

    /**
     * Downloads a file from S3 and saves it locally.
     *
     * @param s3Key          Key (path) of the file in the S3 bucket.
     * @throws Exception 
     */
    public void downloadFileFromS3(String s3Key) throws Exception {
        String destinationPath = "src/main/java/Local/files/" + s3Key;
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(s3Key)
                    .build();

            s3.getObject(getObjectRequest, Paths.get(destinationPath));
            System.out.println("[DEBUG] File downloaded from S3 and saved to: " + destinationPath);
        } catch (S3Exception e) {
            throw new Exception("[ERROR] File downloaded from S3" + s3Key + "failed: " + e.getMessage());
        }
    }


    //---------------------- EC2 Operations -------------------------------------------

    /**
     * Ensures that a Manager EC2 instance exists and is running.
     * @param n reffers to the n'th worker - corresponds to tag: workern
     * If no Manager exists, it creates and runs one.
     */
    private boolean ensureWorkerInstance(int n) {
        try {
        this.workerInstanceIds[n] = getWorkerInstanceId(n);
        
        if (workerInstanceIds[n] != null) {
            System.out.println("[INFO] Worker "+n+"  instance found: " + workerInstanceIds[n]);

            String instanceState = getInstanceState(workerInstanceIds[n]);
            switch (instanceState) {
                case "running":
                    System.out.println("[INFO] Worker "+n+" instance is already running.");
                    break;

                case "stopped":
                    System.out.println("[INFO] Worker "+n+" instance is stopped. Starting it...");
                    startInstance(workerInstanceIds[n]);
                    break;

                case "terminated":
                    System.out.println("[INFO] Worker "+n+" instance is terminated. Creating a new one...");
                    workerInstanceIds[n] = createWorker(n);
                    break;

                default:
                    System.out.println("[WARN] Worker "+n+"  instance is in an unsupported state: " + instanceState);
                    break;
            }
        } else {
            System.out.println("[INFO] No Worker "+n+"  instance found. Creating one...");
            workerInstanceIds[n] = createWorker(n);
            
            if (workerInstanceIds[n] != null) {
                System.out.println("[INFO] Manager instance created and running: " + workerInstanceIds[n]);
            } else {
                throw new Exception("[ERROR] Failed to create Manager instance.");
            }
        }
        return true;
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return false;
        }
    }

    /**
     * Gets the ID of the Manager instance if it exists.
     * @param n reffers to the n'th worker - corresponds to tag: workern
     * @return The instance ID of the Manager, or null if no instance is found.
     */
    private String getWorkerInstanceId(int n) throws Exception {
        try {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(
                            Filter.builder()
                                    .name("tag:Name")
                                    .values("worker"+n)
                                    .build()
                    )
                    .build();

            DescribeInstancesResponse response = ec2.describeInstances(request);

            return response.reservations().stream()
                    .flatMap(reservation -> reservation.instances().stream())
                    .findFirst()
                    .map(instance -> instance.instanceId())
                    .orElse(null);
        } catch (Ec2Exception e) {
            throw new Exception("[ERROR] Failed to get Worker "+n+" instance's Id: " + e.getMessage());
        }
    }


    /**
     * Starts an EC2 instance.
     * 
     * @param instanceId The ID of the instance to start.
     */
    private void startInstance(String instanceId) throws Exception {
        try {
            ec2.startInstances(startInstancesRequest -> startInstancesRequest.instanceIds(instanceId));
            System.out.println("[INFO] Instance started: " + instanceId);
        } catch (Ec2Exception e) {
            throw new Exception("[ERROR] Failed to start instance: " + e.getMessage());
        }
    }



    /**
     * Creates a T2_MICRO EC2 instance with the <Name, manager> tag.
     * @param n reffers to the n'th worker - corresponds to tag: workern
     * @return The Instance ID of the created Manager instance.
     */
    private String createWorker(int n) throws Exception{
        try {
            // Generate the User Data script
            String userDataScript = generateWorkerDataScript(BUCKET_NAME, WORKER_PROGRAM_S3KEY);

            // Encode the User Data script in Base64 as required by AWS
            String userDataEncoded = Base64.getEncoder().encodeToString(userDataScript.getBytes());

            IamInstanceProfileSpecification role = IamInstanceProfileSpecification.builder()
                    .name("LabInstanceProfile") // Ensure this profile has required permissions
                    .build();

            // Create the EC2 instance
            RunInstancesRequest runRequest = RunInstancesRequest.builder()
                    .imageId(ami) // Use the predefined AMI
                    .instanceType(InstanceType.T2_MICRO)
                    .maxCount(1)
                    .minCount(1)
                    .iamInstanceProfile(role)
                    .userData(userDataEncoded)
                    .build();

            RunInstancesResponse response = ec2.runInstances(runRequest);
            String instanceId = response.instances().get(0).instanceId();

            // Add a tag <Name, manager>
            Tag tag = Tag.builder()
                    .key("Name")
                    .value("worker"+n)
                    .build();

            CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                    .resources(instanceId)
                    .tags(tag)
                    .build();
            ec2.createTags(tagRequest);

            System.out.printf("[DEBUG] Worker "+n+" instance created: %s\n", instanceId);
            return instanceId;

        } catch (Ec2Exception e) {
            throw new Exception("[ERROR] Failed to create Worker"+n+" instance: " + e.getMessage());
        }
    }

    // Make sure userScript is correct
    private String generateWorkerDataScript(String s3BucketName, String jarFileName) {
        return "#!/bin/bash\n" +
               "yum update -y\n" +  // Update packages
               "mkdir -p /home/ec2-user/app\n" +  // Create a directory for the app
               "mkdir -p /home/ec2-user/app/files\n" + // create a directory for Worker files
               "cd /home/ec2-user/app\n" +
               "aws s3 cp s3://" + s3BucketName + "/" + jarFileName + " ./\n" +  // Download the JAR file
               "java -jar " + jarFileName + " > app.log 2>&1 &\n";  // Run the JAR in the background and log output
    }
    



    /**
    * Retrieves the current state of an EC2 instance.
    *
    * @param instanceId The ID of the instance.
    * @return The state of the instance (e.g., "running", "stopped", "terminated").
    */
    private String getInstanceState(String instanceId) throws Exception {
        try {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();

            DescribeInstancesResponse response = ec2.describeInstances(request);

            return response.reservations().stream()
                    .flatMap(reservation -> reservation.instances().stream())
                    .findFirst()
                    .map(instance -> instance.state().nameAsString())
                    .orElse("unknown");

        } catch (Ec2Exception e) {
            throw new Exception("[ERROR] Failed to get instance state: " + e.getMessage());
        }
    }



    //---------------------- SQS Operations -------------------------------------------

    /**
     * Creates a fifo SQS queue with the specified name.
     */
    private boolean createSqsQueue(String queueName) {
        try {
            // Define the FIFO-specific attributes
             Map<QueueAttributeName, String> attributes = new HashMap<>();
            attributes.put(QueueAttributeName.FIFO_QUEUE, "true"); // Mark as FIFO queue
            attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"); // Optional: Enable content-based deduplication
            CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributes(attributes)
                    .build();
            sqs.createQueue(createQueueRequest);
            System.out.println("[DEBUG] Queue created successfully: " + queueName);
            return true;
        } catch (SqsException e) {
            System.err.println("[ERROR] creating "+queueName+" SQS: " + e.getMessage());
            return false;
        }

    }

    private boolean createWorkersUpStreamQueue() {
        return createSqsQueue(MANAGER_WORKERS_Q);
    }

    private boolean createWorkersDownStreamQueue() {
        return createSqsQueue(WORKERS_MANAGER_Q);
    }


    /**
     * Sends a message to the specified SQS queue.
     *
     * @param messageBody Message to send.
     */
    public void sendMessageToLocalSqs(String messageBody) throws Exception {
        try {
            // Get the queue's URL
            GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder()
                .queueName(MANAGER_LOCAL_Q)
                .build();
            String queueUrl = sqs.getQueueUrl(getQueueUrlRequest).queueUrl();
            // send the message
            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .messageGroupId(MANAGER_LOCAL_MESSAGE_GROUP_ID)
                    .build();
            sqs.sendMessage(sendMessageRequest);
            System.out.println("[DEBUG] Message sent to ManagerToLocalSQS: " + messageBody);
        } catch (SqsException e) {
            throw new Exception("[ERROR] Message send to ManagerToLocalSQS failed: " + e.getMessage());
        }
    }

    /**
     * Sends a message to the specified SQS queue.
     *
     * @param messageBody Message to send.
     */
    public void sendMessageToWorkersSqs(String messageBody) throws Exception {
        try {
            // Get the queue's URL
            GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder()
                .queueName(MANAGER_WORKERS_Q)
                .build();
            String queueUrl = sqs.getQueueUrl(getQueueUrlRequest).queueUrl();
            // send the message
            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .messageGroupId(MANAGER_WORKERS_MESSAGE_GROUP_ID)
                    .build();
            sqs.sendMessage(sendMessageRequest);
            System.out.println("[DEBUG] Message sent to ManagerToWorkersSQS: " + messageBody);
        } catch (SqsException e) {
            throw new Exception("[ERROR] Message send to ManagerToWorkersSQS failed: " + e.getMessage());
        }
    }

    /**
     * Receives a message from the SQS queue.
     * @return The received message body, or null if no message is available.
     */
    public String receiveMessageFromLocalSqs() throws Exception{
        try {
            // Get the queue's URL
            GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder()
                .queueName(LOCAL_MANAGER_Q)
                .build();
            String queueUrl = sqs.getQueueUrl(getQueueUrlRequest).queueUrl();
            // receive the message
            ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(10)
                    .build();
            var messages = sqs.receiveMessage(receiveMessageRequest).messages();
            if (!messages.isEmpty()) {
                var message = messages.get(0);
                deleteMessageFromSqs(queueUrl, message.receiptHandle());
                System.out.println("[DEBUG] Message received from LocalToManagerSQS: " + message.body());
                return message.body(); 
            }
        } catch (SqsException e) {
            throw new Exception("[ERROR] Messsage recieving from LocalToManagerSQS failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Receives a message from the SQS queue.
     * @return The received message body, or null if no message is available.
     */
    public String receiveMessageFromWorkersSqs() throws Exception{
        try {
            // Get the queue's URL
            GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder()
                .queueName(WORKERS_MANAGER_Q)
                .build();
            String queueUrl = sqs.getQueueUrl(getQueueUrlRequest).queueUrl();
            // receive the message
            ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(10)
                    .build();
            var messages = sqs.receiveMessage(receiveMessageRequest).messages();
            if (!messages.isEmpty()) {
                var message = messages.get(0);
                deleteMessageFromSqs(queueUrl, message.receiptHandle());
                System.out.println("[DEBUG] Message received from WorkersToManagerSQS: " + message.body());
                return message.body();
            }
        } catch (SqsException e) {
            throw new Exception("[ERROR] Messsage recieving from WorkersToManagerSQS failed: " + e.getMessage());
        }
        return null;
    }


    
    /**
     * Deletes a message from the specified SQS queue.
     * helper for receiveMessageFromSqs
     *
     * @param queueUrl     URL of the SQS queue.
     * @param receiptHandle Receipt handle of the message to delete.
     */
    private void deleteMessageFromSqs(String queueUrl, String receiptHandle) {
        try {
            DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();

            sqs.deleteMessage(deleteMessageRequest);
            System.out.println("[DEBUG] Message deleted from SQS: " + receiptHandle);
        } catch (SqsException e) {
            System.err.println("[ERROR] " + e.getMessage());
        }
    }

    //---------------------- FUNC -------------------------------------------




}

