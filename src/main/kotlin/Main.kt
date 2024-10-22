import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ibm.mq.*
import com.ibm.mq.constants.CMQC.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.coroutines.CoroutineContext

class MQMessageListener(
    private val queueManager: MQQueueManager,
    private val queueName: String,
    private val onMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : CoroutineScope {
    private val logger = LoggerFactory.getLogger(MQMessageListener::class.java)
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private var isListening = false

    fun startListening() {
        if (isListening) return

        isListening = true
        launch {
            logger.info("Starting message listener for queue: $queueName")
            try {
                val queue =
                    queueManager.accessQueue(
                        queueName,
                        MQOO_INPUT_AS_Q_DEF or MQOO_FAIL_IF_QUIESCING or MQOO_BROWSE,
                    )

                while (isListening && isActive) {
                    try {
                        val message = MQMessage()
                        val gmo =
                            MQGetMessageOptions().apply {
                                options = MQGMO_WAIT or MQGMO_FAIL_IF_QUIESCING
                                waitInterval = 5000 // 5 second wait timeout
                            }

                        queue.get(message, gmo)
                        val messageContent = message.readStringOfCharLength(message.messageLength)
                        logger.debug("Received message: $messageContent")
                        onMessage(messageContent)
                    } catch (e: MQException) {
                        if (e.reasonCode == MQRC_NO_MSG_AVAILABLE) {
                            // No message available, continue waiting
                            continue
                        }
                        logger.error("Error while listening for messages", e)
                        onError(e)
                    }
                }
                queue.close()
            } catch (e: Exception) {
                logger.error("Fatal error in message listener", e)
                onError(e)
                isListening = false
            }
        }
    }

    fun stopListening() {
        logger.info("Stopping message listener for queue: $queueName")
        isListening = false
        job.cancel()
    }
}

class MQClient(
    private val host: String,
    private val port: Int,
    private val channel: String,
    private val queueManagerStr: String,
    private val username: String,
    private val password: String,
) {
    private var queueManager: MQQueueManager? = null
    private val logger = LoggerFactory.getLogger(MQClient::class.java)
    private val messageListeners = mutableMapOf<String, MQMessageListener>()

    // ... (previous connect, sendMessage, receiveMessage methods remain the same)
    fun connect(): Result<Unit> =
        try {
            logger.info("Attempting to connect to MQ server at $host:$port")
            logger.debug("Connection parameters: channel=$channel, queueManager=$queueManager, username=$username")

            val properties =
                Hashtable<String, Any>().apply {
                    put(HOST_NAME_PROPERTY, host)
                    put(PORT_PROPERTY, port)
                    put(CHANNEL_PROPERTY, channel)
                    put(USER_ID_PROPERTY, username)
                    put(PASSWORD_PROPERTY, password)
                    put(TRANSPORT_PROPERTY, TRANSPORT_MQSERIES)
                }

            queueManager = MQQueueManager(queueManagerStr, properties)
            logger.info("Successfully connected to MQ server")
            Result.success(Unit)
        } catch (e: MQException) {
            logger.error("Failed to connect to MQ server", e)
            logger.error("MQ Completion Code: ${e.completionCode}")
            logger.error("MQ Reason Code: ${e.reasonCode}")
            logger.error("MQ Error Message: ${e.localizedMessage}")
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected error while connecting to MQ server", e)
            Result.failure(e)
        }

    fun sendMessage(
        queueName: String,
        message: String,
    ): Result<Unit> =
        try {
            logger.info("Attempting to send message to queue: $queueName")
            queueManager?.let { qMgr ->
                logger.debug("Opening queue for sending message")
                val queue =
                    qMgr.accessQueue(
                        queueName,
                        MQOO_OUTPUT or MQOO_FAIL_IF_QUIESCING,
                    )
                val mqMessage =
                    MQMessage().apply {
                        writeString(message)
                    }
                logger.debug("Sending message of length: ${message.length}")
                queue.put(mqMessage)
                queue.close()
                logger.info("Successfully sent message to queue: $queueName")
                Result.success(Unit)
            } ?: run {
                logger.error("Cannot send message: Queue manager not connected")
                Result.failure(IllegalStateException("Queue manager not connected"))
            }
        } catch (e: MQException) {
            logger.error("Failed to send message to queue: $queueName", e)
            logger.error("MQ Completion Code: ${e.completionCode}")
            logger.error("MQ Reason Code: ${e.reasonCode}")
            Result.failure(e)
        }

    fun receiveMessage(queueName: String): Result<String> =
        try {
            logger.info("Attempting to receive message from queue: $queueName")
            queueManager?.let { qMgr ->
                logger.debug("Opening queue for receiving message")
                val queue =
                    qMgr.accessQueue(
                        queueName,
                        MQOO_INPUT_AS_Q_DEF or MQOO_FAIL_IF_QUIESCING,
                    )
                val mqMessage = MQMessage()
                val gmo = MQGetMessageOptions()
                queue.get(mqMessage, gmo)
                val message = mqMessage.readStringOfCharLength(mqMessage.messageLength)
                queue.close()
                logger.info("Successfully received message from queue: $queueName")
                logger.debug("Message length: ${message.length}")
                Result.success(message)
            } ?: run {
                logger.error("Cannot receive message: Queue manager not connected")
                Result.failure(IllegalStateException("Queue manager not connected"))
            }
        } catch (e: MQException) {
            logger.error("Failed to receive message from queue: $queueName", e)
            logger.error("MQ Completion Code: ${e.completionCode}")
            logger.error("MQ Reason Code: ${e.reasonCode}")
            Result.failure(e)
        }

    fun startListening(
        queueName: String,
        onMessage: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ): Result<Unit> =
        try {
            logger.info("Setting up message listener for queue: $queueName")
            queueManager?.let { qMgr ->
                val listener = MQMessageListener(qMgr, queueName, onMessage, onError)
                messageListeners[queueName] = listener
                listener.startListening()
                Result.success(Unit)
            } ?: run {
                val error = IllegalStateException("Queue manager not connected")
                logger.error("Cannot start listener: Queue manager not connected")
                Result.failure(error)
            }
        } catch (e: Exception) {
            logger.error("Error setting up message listener", e)
            Result.failure(e)
        }

    fun stopListening(queueName: String) {
        logger.info("Stopping message listener for queue: $queueName")
        messageListeners[queueName]?.stopListening()
        messageListeners.remove(queueName)
    }

    fun disconnect() {
        try {
            logger.info("Disconnecting from MQ server")
            messageListeners.forEach { (queueName, listener) ->
                listener.stopListening()
            }
            messageListeners.clear()
            queueManager?.disconnect()
            queueManager = null
            logger.info("Successfully disconnected from MQ server")
        } catch (e: MQException) {
            logger.error("Error while disconnecting from MQ server", e)
            logger.error("MQ Completion Code: ${e.completionCode}")
            logger.error("MQ Reason Code: ${e.reasonCode}")
        }
    }
}

@Composable
fun MessageList(messages: List<String>) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(200.dp),
    ) {
        items(messages) { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(4.dp),
            )
        }
    }
}

@Composable
fun MainScreen() {
    val logger = LoggerFactory.getLogger("MainScreen")

    var host by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf("1414") }
    var channel by remember { mutableStateOf("DEV.APP.SVRCONN") }
    var queueManager by remember { mutableStateOf("QM1") }
    var username by remember { mutableStateOf("app") }
    var password by remember { mutableStateOf("") }
    var queueName by remember { mutableStateOf("DEV.QUEUE.1") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    var receivedMessages by remember { mutableStateOf(listOf<String>()) }
    var isListening by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<MQClient?>(null) }

    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Connection settings
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
        )
        OutlinedTextField(
            value = channel,
            onValueChange = { channel = it },
            label = { Text("Channel") },
        )
        OutlinedTextField(
            value = queueManager,
            onValueChange = { queueManager = it },
            label = { Text("Queue Manager") },
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            client =
                                MQClient(
                                    host,
                                    port.toInt(),
                                    channel,
                                    queueManager,
                                    username,
                                    password,
                                )
                            withContext(Dispatchers.IO) {
                                client
                                    ?.connect()
                                    ?.onSuccess { status = "Connected successfully" }
                                    ?.onFailure { status = "Connection failed: ${it.message}" }
                            }
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                        }
                    }
                },
            ) {
                Text("Connect")
            }

            Button(
                onClick = {
                    client?.disconnect()
                    client = null
                    status = "Disconnected"
                },
            ) {
                Text("Disconnect")
            }
        }

        Divider()

        // Message listening controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        if (!isListening) {
                            client?.startListening(
                                queueName,
                                onMessage = { msg ->
                                    receivedMessages = (receivedMessages + msg).takeLast(100)
                                    status = "Message received"
                                },
                                onError = { error ->
                                    status = "Listener error: ${error.message}"
                                    isListening = false
                                },
                            )
                            isListening = true
                            status = "Started listening on queue: $queueName"
                        } else {
                            client?.stopListening(queueName)
                            isListening = false
                            status = "Stopped listening on queue: $queueName"
                        }
                    }
                },
            ) {
                Text(if (isListening) "Stop Listening" else "Start Listening")
            }
        }

        // Message list
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            elevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
            ) {
                Text("Received Messages:", style = MaterialTheme.typography.h6)
                MessageList(receivedMessages)
            }
        }

        // Previous message sending controls remain the same...

        // Status display
        Text(
            text = status,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

fun main() =
    application {
        val logger = LoggerFactory.getLogger("Application")
        logger.info("Starting IBM MQ Client application")

        Window(
            onCloseRequest = {
                logger.info("Application shutdown initiated")
                exitApplication()
            },
            title = "IBM MQ Client",
        ) {
            MaterialTheme {
                MainScreen()
            }
        }
    }
