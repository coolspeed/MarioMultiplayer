import processing.core.*;
import ddf.minim.*;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedList;
import java.lang.Math;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Socket;
import com.google.gson.Gson;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.yaml.snakeyaml.Yaml;


public class MarioMultiplay extends PApplet {

    // In Eclipse, run this project as Java Application (not Applet)
    public static void main(String[] args) {
        String[] a = { "MarioMultiplay" };
        PApplet.runSketch(a, new MarioMultiplay());
    }
    
    public void settings() {
        size(WIDTH, HEIGHT, P2D);
    }

    
    // ============================= NEW FILE ==================================


    enum GAME_STATE {
        PLAYING,
        WIN,
        LOSE
    }
    
    // Global constants

    int FRAME_RATE = 60;
    int FRAMES_PER_SHOT = FRAME_RATE / 10;
    int WIDTH = 640;
    int HEIGHT = 432;
    int ASSET_SCALE = 2;
    float GRAVITY = 0.7f;
    float MARIO_JUMP_FORCE = 12f;
    float BOOST_JUMP_FORCE = 4.8f;
    float MARIO_VX_LIMIT = 4.7f;
    float MARIO_FORCE = 0.2f;
    int CAMERA_RANGE_LEFT = 170;
    int BLOCK_OFFSET = -16;
    String SERVER_HOST;
    String XSUB_PORT = "1234";
    String XPUB_PORT = "5678";
    int MY_MARIO_NUM;
    String ROOM_NUM;
    float MY_FRICTIONAL_FORCE = 0.4f;  // 0.4 for normal world.  0.1 for ice world.
    boolean SHOW_PACKET_INDICATOR;
    boolean PACKET_FRUGAL = true;
    
    // Global variables
    
    GAME_STATE gameState = GAME_STATE.PLAYING;
    long frame = 0;
    Set<String> pendingKeyboardEvents;
    boolean needSync = true;
    
    // Global objects
    
    Mario marioMe;
    Mario marioRival;
    Mario mario0;
    Mario mario1;
    Sprite bgImgSprite;
    Sprite princessSprite;
    BlockManager blockManager;
    CollisionDetector collisionDetector;
    EnemyCollisionDetector enemyCD;
    NetworkManager networkManager;
    UIManger uiManager;
    // Audio ------------- START
    AudioPlayer player;
    Minim minim;  // Audio context
    // Audio ------------- END
    Gson gson;
    
    Context pubContext;
    Socket pubSocket;
    ConcurrentLinkedQueue<String> msgQueue;
    

    public void setup() {
        background(51);
        frameRate(FRAME_RATE);
        
        // Config file
        Yaml yaml = new Yaml();
        String[] lines = loadStrings("config.yml");
        StringBuilder sb = new StringBuilder();
        for (String line: lines) sb.append(line).append("\n");
        String document = sb.toString();
        Map confMap = (Map) yaml.load(document);
        ROOM_NUM = (String) confMap.get("room_num");
        System.out.println("ROOM_NUM=" + ROOM_NUM);
        MY_MARIO_NUM = (int) confMap.get("my_mario_num");
        System.out.println("MY_MARIO_NUM=" + MY_MARIO_NUM);
        MY_FRICTIONAL_FORCE = (float) (double) confMap.get("my_frictional_force");
        System.out.println("MY_FRICTIONAL_FORCE=" + MY_FRICTIONAL_FORCE);
        SERVER_HOST = (String) confMap.get("server_host");
        System.out.println("SERVER_HOST=" + SERVER_HOST);
        SHOW_PACKET_INDICATOR = (boolean) confMap.get("show_packet_indicator");
        System.out.println("SHOW_PACKET_INDICATOR=" + SHOW_PACKET_INDICATOR);
        PACKET_FRUGAL = (boolean) confMap.get("packet_frugal");
        System.out.println("PACKET_FRUGAL=" + PACKET_FRUGAL);
        
        // Backgroud image
        bgImgSprite = new SharedSprite("img/background_", 1);
        
        // Princess
        princessSprite = new SharedSprite("img/princess_standing_", 1);
        
        // Block manager
        blockManager = new BlockManager();
        
        // Collision detector
        collisionDetector = new CollisionDetector();
        enemyCD = new EnemyCollisionDetector();
        
        // Well, our main character appears
        marioMe = new Mario(false);  // Color
        marioRival = new Mario(true);  // Black and White
        
        // marioMe, marioRival
        if (MY_MARIO_NUM == 0) {
            mario0 = marioMe;
            mario1 = marioRival;
        } else {
            mario1 = marioMe;
            mario0 = marioRival;
        }
        // Mario1 positioned at the right of mario0
        mario1.x = mario0.x + 300;
        
        // BGM
        minim = new Minim(this);
        player = minim.loadFile("Supermario_BGM_Overworld.mp3");
        player.play();
        
        // Network manager
        networkManager = new NetworkManager();
        
        // UI manager
        uiManager = new UIManger();
        
        // Gson
        gson = new Gson();
        
        // Publisher
        pubContext = ZMQ.context(1);
        pubSocket = pubContext.socket(ZMQ.PUB);
        pubSocket.connect("tcp://" + SERVER_HOST + ":" + XSUB_PORT);
        
        // Message queue
        msgQueue = new ConcurrentLinkedQueue<String>();
        
        // Keyboard event queue
        pendingKeyboardEvents = new HashSet<String>();
        
        // Subscriber thread mst be launched after msgQueue is initialized!
        new Thread(new Subscriber()).start();
    }
    
    
    public void stop() {
        // Audio player
        player.close();
        minim.stop();
        
        // ZeroMQ publisher
        pubSocket.close();
        pubContext.term();
        
        super.stop();
    }
    
    
    public void draw() {
        processUserInput();
        
        processGameLogic();
        
        doNetworkStuff();
        
        uiManager.update();

        render();
        
        ++frame;
    }

    
    // ============================= USER INPUT ================================

    
    public void processUserInput() {
        marioMe.processKeyboard();
    }
    
    /**
     * Called once every time a key is pressed. The key that was pressed is
     * stored in the key variable.
     */
    public void keyPressed() {
        marioMe.onKeyPress(key);
    }

    /**
     * Called once every time a key is released. The key that was released will
     * be stored in the 'key' member variable of PApplet.
     */
    public void keyReleased() {
        marioMe.onKeyRelease(key);
    }

    
    // ============================= GAME LOGIC ================================

    
    public void processGameLogic() {
        // Game over?
        if (gameState != GAME_STATE.PLAYING) return;
        
        // Update the shared sprites
        blockManager.spriteGold.update();
        
        // Do the simulation
        marioMe.update();
        marioRival.update();
    }
    
    
    // ======================== NETWORK COMMUNICATION ===========================

    
    public void doNetworkStuff() {
        networkWrite();    // First, send my mario's state, which is already set,
        networkRead();     // then set up ghost mario's state from what received.
    }
    
    public void networkRead() {
        // Game over?
        if (gameState != GAME_STATE.PLAYING) return;
        
        // Process all unread msgs
        ArrayList<String> unreadMsgs = networkManager.getUnreadMsgs();
        for (String msg: unreadMsgs) {
            if (gameState == GAME_STATE.PLAYING) {
                processMsg(msg);
                uiManager.addInPacket();
            } else {  // Game over
                ;  // Throw packet
            }
        }
    }
    
    public void networkWrite() {
        // Game over?
        if (gameState != GAME_STATE.PLAYING) return;
        
        // Need sync?
        if (PACKET_FRUGAL && ! needSync) return;
        
        Packet packet = networkManager.makeMarioStatePacket(marioMe);
        String packetStr = networkManager.packetToString(packet);
        String channel = ROOM_NUM + "CHANNEL_MARIO_" + MY_MARIO_NUM;
        pubSocket.sendMore(channel);
        pubSocket.send(packetStr);
        
        // Notify UIManager
        uiManager.addOutPacket();
    }
    
    public void processMsg(String msg) {
        if (frame < 100) System.out.println("Frame " + frame + ": " + msg);
        
        Packet packet = networkManager.stringToPacket(msg);
        
        // Judge packet type
        switch (packet.type) {
        case "GAME_OVER":
            if (packet.whoWon == MY_MARIO_NUM) {  // Win
                gameState = GAME_STATE.WIN;
            } else if (packet.whoWon == (1 - MY_MARIO_NUM)) { // Lose
                gameState = GAME_STATE.LOSE;
            }
            break;
        case "MARIO_STATE":
            // Setting mario state
            marioRival.x = packet.x;
            marioRival.y = packet.y;
            marioRival.vx = packet.vx;
            marioRival.vy = packet.vy;
            marioRival.ax = packet.ax;
            marioRival.ay = packet.ay;
            switch (packet.motionState) {
            case "STANDING": marioRival.motionState = MotionState.STANDING;
                break;
            case "RUNNING": marioRival.motionState = MotionState.RUNNING;
                break;
            case "JUMPING": marioRival.motionState = MotionState.JUMPING;
                break;
            case "FALLING": marioRival.motionState = MotionState.FALLING;
                break;
            default:
                break;
            }
            switch (packet.faceState) {
            case "FACE_FRONT": marioRival.faceState = MarioFace.FACE_FRONT;
                break;
            case "FACE_LEFT": marioRival.faceState = MarioFace.FACE_LEFT;
                break;
            case "FACE_RIGHT": marioRival.faceState = MarioFace.FACE_RIGHT;
                break;
            default:
                break;
            }
            marioRival.boostJumping = packet.boostJumping;
            marioRival.jumpPressed = packet.jumpPressed;
            switch (packet.arrowX) {
            case "LEFT": marioRival.arrowX = MarioArrowX.LEFT;
                break;
            case "RIGHT": marioRival.arrowX = MarioArrowX.RIGHT;
                break;
            default: marioRival.arrowX = MarioArrowX.NONE;
                break;
            }
            switch (packet.arrowY) {
            case "UP": marioRival.arrowY = MarioArrowY.UP;
                break;
            case "DOWN": marioRival.arrowY = MarioArrowY.DOWN;
                break;
            default: marioRival.arrowY = MarioArrowY.NONE;
                break;
            }
            marioRival.frictionalForce = packet.frictionalForce;
            break;
        default: ;
            break;
        }
    }
    
    
    public void gameOverWinning() {
        gameState = GAME_STATE.WIN;
        
        // Send GAME_OVER packet to the rival
        Packet packet = networkManager.makeGameOverPacket(MY_MARIO_NUM);
        String packetStr = networkManager.packetToString(packet);
        String channel = ROOM_NUM + "CHANNEL_MARIO_" + MY_MARIO_NUM;
        pubSocket.sendMore(channel);
        pubSocket.send(packetStr);
    }
    
    public void resetGame() {
        gameState = GAME_STATE.PLAYING;
        marioMe.x = 150 + MY_MARIO_NUM * 300;
        marioMe.y = 0;
        marioMe.vx = 0;
        marioMe.vy = 0;
        marioMe.ax = 0;
        marioMe.ay = GRAVITY;
        needSync = true;
        msgQueue.clear();
    }

    
    // ============================= RENDERING =================================

    
    public void render() {
        // Draw Background
        drawBackground();

        // Draw map
        drawMap();

        // Draw the Marios' sprite
        noFill();
        noStroke();
        marioRival.display();
        marioMe.display();
        
        // Draw UI
        uiManager.display();
        
        // Game Over?
        textSize(50);
        fill(255, 174, 201);
        textAlign(CENTER, CENTER);
        if (gameState == GAME_STATE.WIN) {
            String gameStateStr = "WIN";
            text(gameStateStr, WIDTH / 2, HEIGHT / 2);
        } else if (gameState == GAME_STATE.LOSE) {
            String gameStateStr = "LOSE";
            text(gameStateStr, WIDTH / 2, HEIGHT / 2);
        }
    }

    public void drawBackground() {
        fill(107, 140, 255);
        noStroke();
        rect(0, 0, WIDTH, HEIGHT);
        bgImgSprite.display(-1, 8);
        princessSprite.display(6639, 384 - princessSprite.getHeight());
    }

    public void drawMap() {
        blockManager.display();
    }
    
    
    // =============================== NEW FILE ==================================

    
    enum MotionState {
        STANDING, RUNNING, JUMPING, FALLING,
    }

    enum MarioFace {
        FACE_LEFT, FACE_RIGHT, FACE_FRONT
    }
    
    enum MarioArrowX {
        NONE,
        LEFT,
        RIGHT
    }
    
    enum MarioArrowY {
        NONE,
        UP,
        DOWN
    }

    class Mario {
        // Space state
        int width, height;
        float x = 150, y = 0;
        float vx = 0;
        float vy = 0;
        float ax = 0;
        float ay = GRAVITY;
        
        // Force state
        float frictionalForce = 0;
        
        // Motion state
        MotionState motionState = MotionState.JUMPING;
        MarioFace faceState = MarioFace.FACE_RIGHT;

        // Keyboard State
        MarioArrowX arrowX = MarioArrowX.NONE;
        MarioArrowY arrowY = MarioArrowY.NONE;
        boolean jumpPressed = false;
        
        boolean boostJumping = false;

        // Sprite state
        Sprite currentSprite = null;
        
        // Sprites
        Sprite marioStanding, marioRunning, marioJumping, marioFalling;
        
        Camera camera;
        
        boolean isGhost = false;
        
        Mario(boolean blackWhite) {
            // Load sprites
            if (! blackWhite) {
                marioStanding = new Sprite("img/mario_standing_", 1, true);
                marioRunning = new Sprite("img/mario_running_", 4, true);
                marioJumping = new Sprite("img/mario_jumping_", 1, true);
                marioFalling = new Sprite("img/mario_falling_", 2, true);
            } else {
                marioStanding = new Sprite("img/bw_mario_standing_", 1, true);
                marioRunning = new Sprite("img/bw_mario_running_", 4, true);
                marioJumping = new Sprite("img/bw_mario_jumping_", 1, true);
                marioFalling = new Sprite("img/bw_mario_falling_", 2, true);
                
                isGhost = true;
            }
            
            width = marioStanding.getWidth();
            height = marioStanding.getHeight();
            
            frictionalForce = MY_FRICTIONAL_FORCE;
            
            // Make an own camera
            camera = new Camera();
        }

        public void update() {
            _updateMotion();
            _updateSpeed();
            _updatePosition();
        }

        public void display() {
            // Select sprite
            if (motionState == MotionState.FALLING) currentSprite = marioFalling;
            else if (motionState == MotionState.JUMPING) currentSprite = marioJumping;
            else if (motionState == MotionState.RUNNING) currentSprite = marioRunning;
            else if (motionState == MotionState.STANDING) currentSprite = marioStanding;
            
            // Select face direction
            if (faceState == MarioFace.FACE_LEFT) {
                currentSprite.display(x, y, true);
            } else
                currentSprite.display(x, y);
        }
        
        public float getCenterX() {
            return x + 0.5f * width;
        }
        
        // Top two points of the hitbox
        public Coordinate[] getHitboxTop() {
            Coordinate vertx1 = new Coordinate(x + 2, y);
            Coordinate vertx2 = new Coordinate(x + width - 2, y);
            return new Coordinate[]{vertx1, vertx2};
        }
        
        // Bottom two points of the hitbox
        public Coordinate[] getHitboxBottom() {
            Coordinate vertx1 = new Coordinate(x + 2, y + height);
            Coordinate vertx2 = new Coordinate(x + width - 2, y + height);
            return new Coordinate[]{vertx1, vertx2};
        }
        
        public Hitbox getHitboxHead() {
            Hitbox hitbox = new Hitbox();
            hitbox.topLeft = new Coordinate(x + 2, y);
            hitbox.topRight = new Coordinate(x + width - 2, y);
            hitbox.bottomLeft = new Coordinate(x + 2, y + height - 25);
            hitbox.bottomRight = new Coordinate(x + width - 2, y + height - 25);
            return hitbox;
        }
        
        public Hitbox getHitbox() {
            Hitbox hitbox = new Hitbox();
            hitbox.topLeft = new Coordinate(x + 2, y);
            hitbox.topRight = new Coordinate(x + width - 2, y);
            hitbox.bottomLeft = new Coordinate(x + 2, y + height);
            hitbox.bottomRight = new Coordinate(x + width - 2, y + height);
            return hitbox;
        }
        
        public void onKeyPress(char key) {
            if (key == 'c' || key == 'C') {
                pendingKeyboardEvents.add("_jump");
            } else if (key == 'x' || key == '#') {
                ;
            } else if (key == ENTER) {
                if (gameState != GAME_STATE.PLAYING) resetGame();
            }else if (key == CODED) {
                if (keyCode == UP) {
                    pendingKeyboardEvents.add("_up");
                } else if (keyCode == DOWN) {
                    pendingKeyboardEvents.add("_down");
                }
                if (keyCode == LEFT) {
                    pendingKeyboardEvents.add("_left");
                } else if (keyCode == RIGHT) {
                    pendingKeyboardEvents.add("_right");
                }
            }
        }
        
        public void onKeyRelease(char key) {
            if (key == 'c' || key == 'C') {
                pendingKeyboardEvents.add("_release_jump");
            } else if (key == 'x' || key == '#') {
                ;
            } else if (key == CODED) {
                if (keyCode == UP) {
                    pendingKeyboardEvents.add("_release_up");
                } else if (keyCode == DOWN) {
                    pendingKeyboardEvents.add("_release_down");
                }
                if (keyCode == LEFT) {
                    pendingKeyboardEvents.add("_release_left");
                } else if (keyCode == RIGHT) {
                    pendingKeyboardEvents.add("_release_right");
                }
            }
        }
        
        public void processKeyboard() {
            if (pendingKeyboardEvents.isEmpty()) needSync = false;
            else needSync = true;
            
            for (String event: pendingKeyboardEvents) {
                switch (event) {
                case "_jump": _jump();
                    break;
                case "_left": _left();
                    break;
                case "_right": _right();
                    break;
                case "_up": arrowY = MarioArrowY.UP;
                    break;
                case "_down": arrowY = MarioArrowY.DOWN;
                    break;
                case "_release_jump": jumpPressed = false;
                    break;
                case "_release_left": if (arrowX == MarioArrowX.LEFT) arrowX = MarioArrowX.NONE;;
                    break;
                case "_release_right": if (arrowX == MarioArrowX.RIGHT) arrowX = MarioArrowX.NONE;
                    break;
                case "_release_up": if (arrowY == MarioArrowY.UP) arrowY = MarioArrowY.NONE;
                    break;
                case "_release_down": if (arrowY == MarioArrowY.DOWN) arrowY = MarioArrowY.NONE;
                    break;
                default: ;
                    break;
                }
            }
            pendingKeyboardEvents.clear();
        }

        public void _jump() {
            if (motionState != MotionState.JUMPING) {
                motionState = MotionState.JUMPING;
                vy = -MARIO_JUMP_FORCE;  // Bounce up!  위로 튕겨주다!
            }
            jumpPressed = true;
        }
        
        public void _boostJump() {
            if (! boostJumping) {
                boostJumping = true;
                vy -= BOOST_JUMP_FORCE;
            }
        }
        
        public void _disJump() {
            // Release the jumping state
            if (motionState == MotionState.JUMPING) motionState = MotionState.STANDING;
            boostJumping = false;
            vy = 0;
        }
        
        public void _friction() {
            // Damping to break
            if (vx > 0 && motionState != MotionState.JUMPING) ax = -frictionalForce;
            else if (vx < 0 && motionState != MotionState.JUMPING) ax = frictionalForce;
        }

        public void _left() {
            // Turn the face to left
            arrowX = MarioArrowX.LEFT;
            
            faceState = MarioFace.FACE_LEFT;
            // Run, if were standing. 서있던거면 달려라
            if (motionState == MotionState.STANDING) motionState = MotionState.RUNNING;
            
            // Give a force to left
            if (vx > 0) vx = 0;
            ax = -MARIO_FORCE;
        }
        
        public void _right() {
            arrowX = MarioArrowX.RIGHT;
            
            // Turn the face to right
            faceState = MarioFace.FACE_RIGHT;
            // Run, if were standing. 서있던거면 달려라
            if (motionState == MotionState.STANDING) motionState = MotionState.RUNNING;
            
            // Give a force to right
            if (vx < 0) vx = 0;
            ax = MARIO_FORCE;
        }
        
        public void _updateMotion() {
            if (arrowX == MarioArrowX.LEFT) {
                faceState = MarioFace.FACE_LEFT;
                
                if (motionState == MotionState.STANDING) {  // 달리기 로직
                    motionState = MotionState.RUNNING;
                }
            } else if (arrowX == MarioArrowX.RIGHT) {
                faceState = MarioFace.FACE_RIGHT;
                if (motionState == MotionState.STANDING) {  // 달리기 로직
                    motionState = MotionState.RUNNING;
                }
            } else if (arrowX == MarioArrowX.NONE) {
                // 占쏙옙占쌩깍옙 占쏙옙占쏙옙
                if (motionState == MotionState.RUNNING) motionState = MotionState.STANDING;
                _friction();
            }
        }
        
        public void _updateSpeed() {
            // Y-axis
            vy += ay;
            // Boost jump
            if (vy < 0 && vy > -4 && jumpPressed && ! boostJumping) _boostJump();
            
            // X-axis
            float previousVx = vx;
            vx += ax;
            // Limit speed
            if (Math.abs(vx) > MARIO_VX_LIMIT) {
                if (vx > 0) {
                    vx = MARIO_VX_LIMIT;
                } else {
                    vx = -MARIO_VX_LIMIT;
                }
            } else if (Math.abs(vx) < MARIO_FORCE) {
                vx = 0;
                ax = 0;
            } else if (previousVx * vx < 0) {  // Symbol changed
                vx = 0;
                ax = 0;
            }
        }

        public void _updatePosition() {
            // Game Over?
            if (gameState != GAME_STATE.PLAYING) return;
            
            // Update X
            float previousX = x;
            x += vx;
            if (x < 0) x = 0;
            if (x > 7000) x = 7000;
            
            // Collision detection: X
            if (collisionDetector.left(this) || collisionDetector.right(this)){
                x = previousX;
                vx = 0;
            }
            
            // Update Y
            float previousY = y;
            y += vy;
            
            // Collision detection: Y
            if (collisionDetector.up(this)) {
                y = previousY;
                vy = 0;
            }
            if (y > HEIGHT - mario0.height || collisionDetector.down(this) ) {
                y = previousY;
                vy = 0;
                _disJump();
            }
            
            // Enemy Collision Detection
            if (! isGhost) {  // FTS: Favor The Shooter. 고스트는 밟기 판정 안함
                if (enemyCD.isTrample(getHitboxBottom(), marioRival.getHitboxHead(), this)
                        == HitType.ENEMY) {
                    gameOverWinning();
                }
            }
            
            // Camera following
            if (getCenterX() - camera.getCenterX() > 0) {  // Mario too right
                camera.x = getCenterX() - 0.5f * camera.width;
            } else if (camera.getCenterX() - getCenterX() > CAMERA_RANGE_LEFT) {  // Mario too left
                camera.x = getCenterX() + CAMERA_RANGE_LEFT - 0.5f * camera.width;
                if (camera.x < 0) camera.x = 0;
            }
        }
    }

    // ============================== NEW FILE ==================================

    int framesPerShot = FRAMES_PER_SHOT;

    /**
     * Class for animating a sequence of GIFs
     */
    class Sprite {
        PImage[] images;
        PImage[] mirroredImages;
        int imageCount;
        int frame;

        Sprite(String imagePrefix, int count) {
            imageCount = count;
            images = new PImage[imageCount];

            for (int i = 0; i < imageCount; ++i) {
                // Use nf() to number format 'i' into four digits
                String filename = imagePrefix + nf(i, 4) + ".gif";
                PImage img = loadImage(filename);
                images[i] = _enlarge(img, 2);
            }
        }

        Sprite(String imagePrefix, int count, boolean hasMirrored) {

            imageCount = count;
            images = new PImage[imageCount];

            for (int i = 0; i < imageCount; ++i) {
                // Use nf() to number format 'i' into four digits
                String filename = imagePrefix + nf(i, 4) + ".gif";
                PImage img = loadImage(filename);
                images[i] = _enlarge(img, 2);
            }

            if (hasMirrored) {
                mirroredImages = new PImage[imageCount];
                for (int i = 0; i < imageCount; ++i) {
                    mirroredImages[i] = _flip(images[i]);
                }
            }
        }

        public void display(float xpos, float ypos) {  // 월드 좌표 받고
            frame = (frame + 1) % (imageCount * framesPerShot);
            int shotIndex = frame / framesPerShot;
            image(images[shotIndex], xpos - marioMe.camera.x, ypos, images[shotIndex].width,
                    images[shotIndex].height);  // 카메라 좌표계로 그려줌
        }
        
        public void display(float xpos, float ypos, boolean reverse) {
            if (! reverse) display(xpos - marioMe.camera.x, ypos);
            
            frame = (frame + 1) % (imageCount * framesPerShot);
            int shotIndex = frame / framesPerShot;
            image(mirroredImages[shotIndex], xpos - marioMe.camera.x, ypos, mirroredImages[shotIndex].width,
                    mirroredImages[shotIndex].height);
        }

        public int getWidth() {
            return images[0].width;
        }

        public int getHeight() {
            return images[0].height;
        }

        public PImage _enlarge(PImage image, int multiple) {
            PImage newImg;
            newImg = createImage(image.width * multiple, image.height * multiple, ARGB);
            for (int i = 0; i < image.width; ++i) {
                for (int j = 0; j < image.height; ++j) {
                    newImg.set(2 * i, 2 * j, image.get(i, j));
                    newImg.set(2 * i, 2 * j + 1, image.get(i, j));
                    newImg.set(2 * i + 1, 2 * j, image.get(i, j));
                    newImg.set(2 * i + 1, 2 * j + 1, image.get(i, j));
                }
            }
            
            return newImg;
        }
        
        public PImage _flip(PImage image) {
            PImage reverse;
            reverse = createImage(image.width, image.height, ARGB);

            for (int i = 0; i < image.width; ++i) {
                for (int j = 0; j < image.height; ++j) {
                    int xPixel, yPixel;
                    xPixel = image.width - 1 - i;
                    yPixel = j;
                    reverse.pixels[yPixel * image.width + xPixel] = image.pixels[j * image.width + i];
                }
            }
            return reverse;
        }

    }
    
    class SharedSprite extends Sprite {
        
        public SharedSprite(String imagePrefix, int count) {
            super(imagePrefix, count);
        }
        
        public SharedSprite(String imagePrefix, int count, boolean hasMirrored) {
            super(imagePrefix, count, hasMirrored);
        }
        
        public void display(float xpos, float ypos) {
            int shotIndex = frame / framesPerShot;
            image(images[shotIndex], xpos - marioMe.camera.x, ypos,
                    images[shotIndex].width, images[shotIndex].height);
        }
        
        public void display(float xpos, float ypos, boolean reverse) {
            int shotIndex = frame / framesPerShot;
            
            if (! reverse) {
                image(images[shotIndex], xpos - marioMe.camera.x, ypos,
                        images[shotIndex].width, images[shotIndex].height);
            } else {
                image(mirroredImages[shotIndex], xpos - marioMe.camera.x, ypos,
                    mirroredImages[shotIndex].width, mirroredImages[shotIndex].height);
            }
        }
        
        public void update() {
            frame = (frame + 1) % (imageCount * framesPerShot);
        }
    }
    
    class TextSprite {
        private float x, y;
        private float vy;
        private String _text;
        private int size;
        int r;
        int g;
        int b;
        
        public TextSprite(String text, int size, float x, float y, float vy) {
            this.x = x;
            this.y = y;
            this._text = text;
            this.size = size;
            this.vy = vy;
            
            this.r = (int) random(255);
            this.g = (int) random(255);
            this.b = (int) random(255);
        }
        
        public void update() {
            this.y += vy;
        }
        
        public void display() {
            textSize(size);
            fill(0, 0, 0);
            text(_text, x + 1, y + 1);
            if (PACKET_FRUGAL) fill(255, 255, 255);
            else fill(r, g, b);
            textAlign(CENTER, CENTER);
            text(_text, x, y);
        }
    }
    
    
    // =========================== NEW FILE ==================================
    
    
    class Camera {
        int width, height; // Camera sight
        float x = 0, y = 0;  // Camera's absolute coordinate
        
        Camera() {
            width = WIDTH;
            height = HEIGHT;
        }
        
        float getCenterX() {
            return x + 0.5f * width;
        }
    }

    
    // =========================== NEW FILE ==================================
    
    
    class Block {
        int x = 0, y = 0;
        int xB = 0, yB = 0;
        char blockType;
        boolean visible = true;
        SharedSprite sprite;
        
        public Block(int xB, int yB, char blockType) {
            this.xB = xB;
            this.yB = yB;
            this.x = 16 * ASSET_SCALE * xB + BLOCK_OFFSET;
            this.y = 16 * ASSET_SCALE * yB;
            
            this.visible = true;
            this.blockType = blockType;
        }
        
        public void display() {
            if (sprite != null) {
                sprite.display(x, y);  // 월드 좌표 받음
            }
        }
    }

    class Coordinate {
        float x = 0, y = 0;
        
        public Coordinate(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
    
    class BlockManager {
        SharedSprite spriteGround;
        SharedSprite spriteBrick;
        SharedSprite spriteBlock;
        SharedSprite spriteQuestion;
        SharedSprite spriteGold;
        ArrayList<ArrayList<Block>> blocks = new ArrayList<ArrayList<Block>>();
        
        public BlockManager() {
            // Load sprites
            spriteGround = new SharedSprite("img/ground_", 1);
            spriteBlock = new SharedSprite("img/block_", 1);
            spriteBrick = new SharedSprite("img/brick_", 1);
            spriteQuestion = new SharedSprite("img/question_", 1);
            spriteGold = new SharedSprite("img/gold_", 4);
            
            // Read map.txt
            String[] lines = loadStrings("map.txt");
            // Make blocks
            println("Start to make blocks..");
            for (int i = 0; i < lines.length; ++i) {
                String line = lines[i];
                char[] charArray = line.toCharArray();
                ArrayList<Block> blockRow = new ArrayList<Block>();
                for (int j = 0; j < charArray.length; ++j) {
                    char c = charArray[j];
                    Block block = new Block(j, i, c);
                    switch (c) {
                        case ' ' :
                            break;
                        case '#' : ; block.sprite = spriteGround;     // Ground
                            break;
                        case 'B' : block.sprite = spriteBlock;        // Block
                            break;
                        case 'R' : block.sprite = spriteBrick;        // Block
                            break;
                        case '?' : block.sprite = spriteQuestion;     // Question mark
                            break;
                        case 'O' : block.sprite = spriteGold;         // Gold
                            break;
                        default :
                            break;
                    }
                    
                    blockRow.add(block);
                    print(c);
                }
                blocks.add(blockRow);
                println();
            }
        }
        
        public void display() {
            int xStart;
            if ((x2bX(marioMe.camera.getCenterX()) - 11) < 0) xStart = 0;
            else xStart = x2bX(marioMe.camera.getCenterX()) - 11;
            int xEnd = xStart + 23;
            
            for (int i = 0; i < blocks.size(); ++i) {
                for (int j = xStart; j < Math.min(xEnd + 1, blocks.get(i).size()); ++j) {
                    Block block = blocks.get(i).get(j);
                    block.display();
                }
            }
        }
        
        public Coordinate block2Coor(int bX, int bY) {
            Coordinate coordinate = new Coordinate(16 * ASSET_SCALE * bX,
                    16 * ASSET_SCALE * bY);
            return coordinate;
        }
        
        public int y2bY(float y) {
            return (int) y / (16 * ASSET_SCALE);
        }
        
        public int x2bX(float x) {
            return (int) (x - BLOCK_OFFSET) / (16 * ASSET_SCALE);
        }
    }
    
    
    // ============================ NEW FILE ==================================
    
    
    class CollisionDetector {
        public boolean up(Mario mario) {
            if ((int)mario.y <= 0) return false;
            
            int step = 24;
            for (int i = 0; i < 2; ++i) {
                float x = mario.x + i * step;
                int bX = blockManager.x2bX(x);
                int bY = blockManager.y2bY(mario.y);
                 
                ArrayList<Block> blockRow = blockManager.blocks.get(bY);
                if (blockRow.size() <= bX) continue;  // No block
                 
                Block block = blockRow.get(bX);
                if (block == null) continue;
                if (block.blockType == ' ' || block.blockType == 'O')
                    continue;
                else return true;
            }
            
            // 위에서 true로 미리 빠져나가지 않았다
            return false;
        }
        
        public boolean down(Mario mario) {
            if ((int)mario.y <= 0) return false;
            
            int step = 24;
            for (int i = 0; i < 2; ++i) {
                float x = mario.x + i * step;
                int bX = blockManager.x2bX(x);
                int bY = blockManager.y2bY(mario.y + mario.height);
                 
                ArrayList<Block> blockRow = blockManager.blocks.get(bY);
                if (blockRow.size() <= bX) continue;  // No block
                 
                Block block = blockRow.get(bX);
                if (block == null) continue;
                if (block.blockType == ' ' || block.blockType == 'O') continue;
                else return true;
            }
            
            // 위에서 true로 미리 빠져나가지 않았다
            return false;
        }
        
        public boolean left(Mario mario) {
            if ((int)mario.y <= 0) return false;
            
            int step = mario.height / 2;
            for (int i = 0; i < 3; ++i) {
                float y = mario.y + i * step;
                int bX = blockManager.x2bX(mario.x);
                int bY = blockManager.y2bY(y);
                
                ArrayList<Block> blockRow = blockManager.blocks.get(bY);
                if (blockRow.size() <= bX) continue;  // No block
                
                Block block = blockRow.get(bX);
                if (block == null) continue;
                if (block.blockType == ' ' || block.blockType == 'O') continue;
                else return true;
            }
            
            // 위에서 true로 미리 빠져나가지 않았다
            return false;
        }
        
        public boolean right(Mario mario) {
            if ((int)mario.y <= 0) return false;
            
            int step = mario.height / 2;
            for (int i = 0; i < 3; ++i) {
                float y = mario.y + i * step;
                int bX = blockManager.x2bX(mario.x + mario.width);
                int bY = blockManager.y2bY(y);
                
                ArrayList<Block> blockRow = blockManager.blocks.get(bY);
                if (blockRow.size() <= bX) continue;  // No block
                
                Block block = blockRow.get(bX);
                if (block == null) continue;
                if (block.blockType == ' ' || block.blockType == 'O') continue;
                else return true;
            }
            
            // 위에서 true로 미리 빠져나가지 않았다
            return false;
        }
    }
    
    
    // ============================= NEW FILE ==================================
    
    
    class Rect {
        Coordinate topLeft;
        Coordinate topRight;
        Coordinate bottomLeft;
        Coordinate bottomRight;
    }
    
    class Hitbox extends Rect {}
    
    enum HitType {
        NONE,
        ENEMY
    }
    
    class EnemyCollisionDetector {
        public HitType isTrample(Coordinate[] myHitboxBottom, Hitbox enemyHitbox,
                Mario marioMe) {
            if (marioMe.vy < 0.1) return HitType.NONE;
            
            Coordinate[] hbVertexes = new Coordinate[]{
                    enemyHitbox.topLeft,
                    enemyHitbox.topRight,
                    enemyHitbox.bottomLeft,
                    enemyHitbox.bottomRight
            };
            for (Coordinate foot: myHitboxBottom) {
                boolean isMinX = true;
                for (Coordinate vertx: hbVertexes) {
                    if (foot.x >= vertx.x) {
                        isMinX = false;
                        break;
                    }
                }
                if (isMinX) continue;
                
                boolean isMaxX = true;
                for (Coordinate vertx: hbVertexes) {
                    if (foot.x <= vertx.x) {
                        isMaxX = false;
                        break;
                    }
                }
                if (isMaxX) continue;
                
                boolean isMinY = true;
                for (Coordinate vertx: hbVertexes) {
                    if (foot.y >= vertx.y) {
                        isMinY = false;
                        break;
                    }
                }
                if (isMinY) continue;
                
                boolean isMaxY = true;
                for (Coordinate vertx: hbVertexes) {
                    if (foot.y <= vertx.y) {
                        isMaxY = false;
                        break;
                    }
                }
                if (isMaxY) continue;
                
                // Neither min nor max, return HIT
                return HitType.ENEMY;
            }
            
            // Not hit
            return HitType.NONE;
        }
    }
    
    
    // ============================= NEW FILE ==================================
    

    class Subscriber implements Runnable {
        Context context;
        Socket subSocket;
        
        // Constructor
        public Subscriber () {
            context = ZMQ.context(1);
            subSocket = context.socket(ZMQ.SUB);
            subSocket.connect("tcp://" + SERVER_HOST + ":" + XPUB_PORT);
            String channel = (ROOM_NUM + "CHANNEL_MARIO_" + String.valueOf(1 - MY_MARIO_NUM));
//			String channel = (ROOM_NUM + "CHANNEL_MARIO_0");

            subSocket.subscribe(channel.getBytes(ZMQ.CHARSET));
        }
        
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                /* It read synchronously, so no need to sleep().
                 * It is copied from the official sample code.
                */

                // Read envelope with address
                String channel = subSocket.recvStr();
                // Read message contents
                String contents = subSocket.recvStr();
                //System.out.println("Received from: " + channel + " Contents: " + contents);
                msgQueue.add(contents);
            }
        }
        
        public void finallize() throws Throwable {
            try{
                if (subSocket != null) subSocket.close();
                if (context != null) context.term();
            }
            finally {
                super.finalize();
            }
        }
    }
    
    
    // ============================= NEW FILE ==================================
    
    
    class Packet {
        public String type;  // "MARIO_STATE" / "GAME_OVER"
        public int whoWon;  // 0, 1, -1
        public float x;
        public float y;
        public float vx;
        public float vy;
        public float ax;
        public float ay;
        public String motionState;
        public String faceState;
        public boolean boostJumping;
        public boolean jumpPressed;
        public String arrowX;
        public String arrowY;
        public float frictionalForce;
    }
    
    
    // ============================= NEW FILE ==================================
    
    
    class NetworkManager {
        public ArrayList<String> getUnreadMsgs() {
            ArrayList<String> unreadMsgs = new ArrayList<String>();
            String msg;
            while ((msg = msgQueue.poll()) != null) {
                unreadMsgs.add(msg);
            }
            return unreadMsgs;
        }

        public Packet makeMarioStatePacket(Mario mario) {
            Packet packet = new Packet();
            packet.type = "MARIO_STATE";
            packet.whoWon = -1;  // For none
            packet.x = mario.x;
            packet.y = mario.y;
            packet.vx = mario.vx;
            packet.vy = mario.vy;
            packet.ax = mario.ax;
            packet.ay = mario.ay;
            String motionState = "";
            switch (mario.motionState) {
                case STANDING: motionState = "STANDING";
                    break;
                case RUNNING: motionState = "RUNNING";
                    break;
                case JUMPING: motionState = "JUMPING";
                    break;
                case FALLING: motionState = "FALLING";
                    break;
                default: ;
                    break;
            }
            packet.motionState = motionState;
            String faceState = "";
            switch (mario.faceState) {
                case FACE_FRONT: faceState = "FACE_FRONT";
                    break;
                case FACE_LEFT: faceState = "FACE_LEFT";
                    break;
                case FACE_RIGHT: faceState = "FACE_RIGHT";
                    break;
                default: ;
                    break;
            }
            packet.faceState = faceState;
            packet.boostJumping = mario.boostJumping;
            packet.jumpPressed = mario.jumpPressed;
            switch (mario.arrowX) {
                case LEFT: packet.arrowX = "LEFT";
                    break;
                case RIGHT: packet.arrowX = "RIGHT";
                    break;
                default: packet.arrowX = "NONE";
                    break;
            }
            switch (mario.arrowY) {
                case UP: packet.arrowY = "UP";
                    break;
                case DOWN: packet.arrowY = "DOWN";
                    break;
                default: packet.arrowY = "NONE";
                    break;
            }
            packet.frictionalForce = mario.frictionalForce;
            
            return packet;
        }
        
        public Packet makeGameOverPacket(int whoWon) {
            Packet packet = new Packet();
            packet.type = "GAME_OVER";
            packet.whoWon = whoWon;
            return packet;
        }
        
        public String packetToString(Packet packet) {
            return gson.toJson(packet);
        }
        
        public Packet stringToPacket(String packetStr) {
            return gson.fromJson(packetStr, Packet.class);
        }
    }
    
    
    // ============================= NEW FILE ==================================
    
    
    class UIManger {
        public LinkedList<TextSprite> inPackets;
        public LinkedList<TextSprite> outPackets;
        
        public UIManger() {
            inPackets = new LinkedList<TextSprite>();
            outPackets = new LinkedList<TextSprite>();
        }
        
        public void update() {
            // inPackets
            for (int i = 0; i < inPackets.size(); ++i) {
                inPackets.get(i).update();
            }
            // GC
            while (true) {
                if (inPackets.isEmpty()) break;
                
                TextSprite textSprite = inPackets.getFirst();
                if (textSprite.y > HEIGHT) {
                    inPackets.removeFirst();
                } else break;
            }
            
            // outPackets
            for (int i = 0; i < outPackets.size(); ++i) {
                outPackets.get(i).update();
            }
            // GC
            while (true) {
                if (outPackets.isEmpty()) break;
                
                TextSprite textSprite = outPackets.getFirst();
                if (textSprite.y < 0) {
                    outPackets.removeFirst();
                } else break;
            }
        }
        
        public void display() {
            if (! SHOW_PACKET_INDICATOR) return;
            
            // inPackets
            for (int i = 0; i < inPackets.size(); ++i) {
                inPackets.get(i).display();
            }
            
            // outPackets
            for (int i = 0; i < outPackets.size(); ++i) {
                outPackets.get(i).display();
            }
        }
        
        public void addInPacket() {
            inPackets.addLast(new TextSprite("@", 30, WIDTH, 0, 20));;
        }
        
        public void addOutPacket() {
            outPackets.addLast(new TextSprite("@", 30, 0, HEIGHT, -20));;
        }
    }
}