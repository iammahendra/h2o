package water;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import jsr166y.ForkJoinPool;
import water.hdfs.Hdfs;
import water.nbhm.NonBlockingHashMap;


/**
 * Start point for creating or joining an <code>H2O</code> Cloud.
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */
public final class H2O {

  static boolean _hdfsActive = false;

  static final String version = "v0.3";
  
  // User name for this Cloud
  public static String NAME;
  
  // The default port for finding a Cloud
  static final int DEFAULT_PORT = 54321;
  public static int WEB_PORT;  // The HTML/web interface port - attach your browser here!
  public static int UDP_PORT;  // Fast/small UDP transfers
  public static int TCP_PORT;  // TCP port for long/large internal transfers

  // The multicast discovery port
  static InetAddress CLOUD_MULTICAST_GROUP;
  static int         CLOUD_MULTICAST_PORT ;

  // Myself, as a Node in the Cloud
  public static H2ONode SELF = null;

  // Initial arguments
  public static String[] ARGS;

  public static final PrintStream OUT = System.out;
  public static final PrintStream ERR = System.err;

  // --------------------------------------------------------------------------
  // The Current Cloud.  A list of all the Nodes in the Cloud.  Changes if we
  // decide to change Clouds via atomic Cloud update.
  static public volatile H2O CLOUD;

  // --- 
  // A Set version of the members.
  public final HashSet<H2ONode> _memset;
  // A dense array indexing all Cloud members.  Fast reversal from "member#" to
  // Node.  No holes.  Cloud size is _members.length.
  public final H2ONode[] _memary;
  // UUID to uniquely identify this cloud during paxos voting
  public final UUID _id;
  // A dense integer identifier that rolls over rarely.  Rollover limits the
  // number of simultaneous nested Clouds we are operating on in-parallel.
  // Really capped to 1 byte, under the assumption we won't have 256 nested
  // Clouds.
  public final char _idx;       // no unsigned byte, so unsigned char instead

  // Is nnn larger than old, counting for wrap around?  Gets confused if we
  // start seeing a mix of more than 128 unique clouds at the same time.  Used
  // to tell the order of Clouds appearing.
  static public boolean larger( int nnn, int old ) {
    assert (0 <= nnn && nnn <= 255);
    assert (0 <= old && old <= 255);
    return ((nnn-old)&0xFF) < 64;
  }
 
  // A Set version of the static configuration InetAddress.
  static ArrayList<H2ONode> NODES = new ArrayList<H2ONode>();
  // Multicast is on by default
  static boolean MULTICAST_ENABLED = true;
  static String NODES_FILE = null;

  // Reverse cloud index to a cloud; limit of 256 old clouds.
  static private final H2O[] CLOUDS = new H2O[256];

  // Construct a new H2O Cloud from the member list
  public H2O( UUID cloud_id, HashSet<H2ONode> memset, int idx ) {
    _id = cloud_id;             // Set the Cloud identity
    _memset = memset;           // Record membership list
    _memary = memset.toArray(new H2ONode[memset.size()]); // As an array
    Arrays.sort(_memary);       // ... sorted!
    _idx = (char)(idx&0x0ff);   // Roll-over at 256
  }

  // One-shot atomic setting of the next Cloud, with an empty K/V store.
  // Called single-threaded from Paxos.  Constructs the new H2O Cloud from a
  // member list.
  void set_next_Cloud( UUID id, HashSet<H2ONode> members) {
    synchronized(this) {
      int idx = _idx+1;         // Unique 1-byte Cloud index
      if( idx == 256 ) idx=1;   // wrap, avoiding zero
      H2O cloud = CLOUD = new H2O(id,members,idx);
      CLOUDS[idx] = cloud;   // Also remember here
    }
    Paxos.print("Announcing new Cloud Membership: ",members,"");
  }

  // Check if the cloud id matches with one of the old clouds
  static boolean isIDFromPrevCloud(H2ONode h2o) {
    if ( h2o == null ) return false;
    long lo = h2o.get_cloud_id_lo();
    long hi = h2o.get_cloud_id_hi();
    for( int i=0; i < 256; i++ )
      if( (CLOUDS[i] != null) &&
          (lo == CLOUDS[i]._id.getLeastSignificantBits() &&
           hi == CLOUDS[i]._id.getMostSignificantBits()))
        return true;
    return false;
  }

  public final int size() { return _memary.length; }

  // *Desired* distribution function on keys & replication factor.  Replica #0
  // is the master, replica #1, 2, 3, etc represent additional desired
  // replication nodes.  Note that this function is just the distribution
  // function - it does not DO any replication, nor does it dictate any policy
  // on how fast replication occurs.  Returns -1 if the desired replica
  // is nonsense, e.g. asking for replica #3 in a 2-Node system.
  public int D( Key key, int repl ) {
    if( repl >= size() ) return -1;

    // Easy Cheesy Stupid:
    return ((key._hash+repl)&0x7FFFFFFF) % size();
  }

  // Find the node index for this H2ONode.  Not so cheap.
  public int nidx( H2ONode h2o ) {
    for( int i=0; i<_memary.length; i++ )
      if( _memary[i]==h2o )
        return i;
    return -1;
  }

  // --------------------------------------------------------------------------
  // The (local) set of Key/Value mappings.
  static final NonBlockingHashMap<Key,Value> STORE = new NonBlockingHashMap();

  // Dummy shared volatile for ordering games
  static public volatile int VOLATILE;

  // PutIfMatch
  // - Atomically update the STORE, returning the old Value on success
  // - Kick the persistence engine as needed
  // - Kick HazKeys to Key Home as needed
  // - Return existing Value on fail, no change.
  //
  // Keys are interned here: I always keep the existing Key, if any.  The
  // existing Key is blind jammed into the Value prior to atomically inserting
  // it into the STORE and interning.
  //
  // Because of the blind jam, there is a narrow unusual race where the Key
  // might exist but be stale (deleted, mapped to a TOMBSTONE), a fresh put()
  // can find it and jam it into the Value, then the Key can be deleted
  // completely (e.g. via an invalidate), the table can resize flushing the
  // stale Key, an unrelated weak-put can re-insert a matching Key (but as a
  // new Java object), and delete it, and then the original thread can do a
  // successful put_if_later over the missing Key and blow the invariant that a
  // stored Value always points to the physically equal Key that maps to it
  // from the STORE.  If this happens, some of replication management bits in
  // the Key will be set in the wrong Key copy... leading to extra rounds of
  // replication.

  public static final Value putIfMatch( Key key, Value val, Value old ) {
    if( old == val ) return old; // Trivial success?
    if( old != null ) {
      if( val.true_ifequals(old) ) return old;
      // Interning: use the same One True Key found in the STORE.
      Key key2 = old._key;
      if( key2 != null ) key = key2; // Use the existing Key, if any
      if( !val.is_sentinel() )
        val._key = key;         // Ignore users key, use the interned key
    }

    // Insert into the K/V store
    Value res = STORE.putIfMatchUnlocked(key,val,old);
    assert res==null || res._key == key || val.is_sentinel(); // Keys matched
    if( res != old )            // Failed?
      return res;               // Return the failure cause
    // make sure that val is not marked as persisted or in progress - keep
    // DO_NOT_PERSIST and CACHE values
    if( (val.persistenceState()==Value.IN_PROGRESS) || (val.persistenceState()==Value.PERSISTED))
      val.setPersistenceState(Value.NOT_STARTED);
    key.is_local_persist(val,res); // Start persisting
    return res;              // Return success
  }

  // Raw put; no marking the memory as out-of-sync with disk.  Used to import
  // initial keys from local storage, or to intern keys.
  public static final Value putIfAbsent_raw( Key key, Value val ) {
    assert val.is_same_key(key);
    Value res = STORE.putIfMatchUnlocked(key,val,null);
    assert res == null;
    return res;
  }

  // Get the value from the store, if the value is a placeholder for a locally
  // stored value, replace it with a proper value and continue.  This WILL
  // return "deleted" Values.
  public static Value get( Key key ) {
    Value v = STORE.get(key);
    if( v == null ) return v;
    if( v.is_deleted() ) return v;
    if( !v.is_sentinel() ) {
      v.touch();
      return v;
    }
    // Unloaded sentinel - so "inflate" from disk
    Value vnew = v.load(key); // At least read size
    assert vnew.is_same_key(key);
    // Attempt to set it atomically in case of a racing put from another thread.
    return (STORE.putIfMatchUnlocked(key,vnew,v) == v)
      ? vnew                    // Success
      // Retry after failed put; it should never again be the Sentinel
      : STORE.get(key);
  }

  public static Value raw_get( Key key ) { return STORE.get(key); }
  public static Key getk( Key key ) { return STORE.getk(key); }
  public static Set<Key> keySet( ) { return STORE.keySet(); }
  public static Collection<Value> values( ) { return STORE.values(); }


  // --------------------------------------------------------------------------
  // The main Fork/Join worker pool
  public static final ForkJoinPool FJP
    = new ForkJoinPool(4/*Runtime.getRuntime().availableProcessors()*/,
                       ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                       null, // handler for catching uncaught exceptions
                       false // stack-mode by default
                       );

  // --------------------------------------------------------------------------
  public static OptArgs OPT_ARGS;
  public static class OptArgs extends Arguments.Opt {
    public String name;               // set_cloud_name_and_mcast()
    public String nodes;              // set_cloud_name_and_mcast()
    public int port;                  // set_cloud_name_and_mcast()
    public String ip;
    public String ice_root;           // ice root directory
    public String hdfs;               // HDFS backend
    public String hdfs_root;          // root of the HDFS installation (for I only)
    public String hdfs_config;        // configuration file of the HDFS
    public String hdfs_datanode;      // Datanode root
  }

  // Start up an H2O Node and join any local Cloud
  public static void main( String[] args ) {
    // Parse args
    Arguments arguments = new Arguments(args);
    OptArgs oa = new OptArgs();
    arguments.extract(oa);
    ARGS = arguments.toStringArray();
    OPT_ARGS = oa;

    // Redirect System.out/.err to the Log system
    Log.hook_sys_out_err();

    startLocalNode();     // start the local node
    // Load up from disk and initialize the persistence layer
    initializePersistence();
    startNetworkServices();  // start server services
    startupFinalize();    // finalizes the startup & tests (if any)
    // Hang out here until the End of Time
  }


  /** Starts the local k-v store.
   * Initializes the local k-v store, local node and the local cloud with itself
   * as the only member. 
   * 
   * @param args Command line arguments
   * @return Unprocessed command line arguments for further processing.
   */
  private static void startLocalNode() {
    set_cloud_name_and_mcast();
    SELF = H2ONode.self();

    // Create the starter Cloud with 1 member
    HashSet<H2ONode> starter = new HashSet<H2ONode>();
    starter.add(SELF);
    CLOUD = new H2O(UUID.randomUUID(),starter,1);
  }

  /** Initializes the network services of the local node. 
   * 
   * Starts the worker threads, receiver threads, heartbeats and all other
   * network related services. 
   */
  private static void startNetworkServices() {
    // We've rebooted the JVM recently.  Tell other Nodes they can ignore task
    // prior tasks by us.  Do this before we receive any packets
    UDPRebooted.build_and_multicast();

    // Start the UDPReceiverThread, to listen for requests from other Cloud
    // Nodes.  There should be only 1 of these, and it never shuts down.
    // Started first, so we can start parsing UDP packets
    new UDPReceiverThread().start();

    // Start the MultiReceiverThread, to listen for multi-cast requests from
    // other Cloud Nodes.  There should be only 1 of these, and it never shuts
    // down.  Started soon, so we can start parsing multicast UDP packets
    new MultiReceiverThread().start();

    // Start the heartbeat thread, to publish the Clouds' existence to other
    // Clouds.  This will typically trigger a round of Paxos voting so we can
    // join an existing Cloud.
    final Thread hbt = new HeartBeatThread();
    hbt.setDaemon(true);
    hbt.start();

    // Start a UDP timeout worker thread.  This guy only handles requests for
    // which we have not recieved a timely response and probably need to
    // arrange for a re-send to cover a dropped UDP packet.
    new UDPTimeOutThread().start();

    // Start the TCPReceiverThread, to listen for TCP requests from other Cloud
    // Nodes.  There should be only 1 of these, and it never shuts down.
    new TCPReceiverThread().start();

    water.web.Server.start();
  }

  /** Finalizes the node startup.
   * 
   * Displays the startup message and runs the tests (if applicable).
   */
  private static void startupFinalize() {
    // Sleep a bit so all my other threads can 'catch up'
    try { Thread.sleep(1000); } catch( InterruptedException e ) { } 
   
    System.out.println("The Cloud '"+NAME+"' is Up ("+version+") on " + SELF+
                       (MULTICAST_ENABLED
                        ? (", discovery address "+CLOUD_MULTICAST_GROUP+":"+CLOUD_MULTICAST_PORT)
                        : ", configuration based on -nodes "+NODES_FILE));
  }
  

  // Parse arguments and set cloud name in any case.  Strip out "-name NAME"
  // and "-nodes <filename>".  Ignore the rest.  Set multi-cast port as a hash
  // function of the name.  Parse node ip addresses from the filename.
  static void set_cloud_name_and_mcast( ) {
    // Assign initial ports
    WEB_PORT = OPT_ARGS.port != 0 ? OPT_ARGS.port : DEFAULT_PORT;
    UDP_PORT = WEB_PORT+1;
    TCP_PORT = WEB_PORT+2;

    // See if we can grab them without bind errors!
    while( !(test_port(WEB_PORT) && test_port(UDP_PORT) && test_port(TCP_PORT)) ) {
      if( OPT_ARGS.port != 0 ) 
        Log.die("Some of the required ports "+(OPT_ARGS.port+0)+
                ", "+(OPT_ARGS.port+1)+
                ", and "+(OPT_ARGS.port+2)+
                "are not available, change -port PORT and try again.");
      WEB_PORT++;               // Try the next available port(s)
      UDP_PORT++;
      TCP_PORT++;
    }
    System.out.println("HTTP listening on port: "+WEB_PORT+", UDP port: "+UDP_PORT+", TCP port: "+TCP_PORT);

    NAME = OPT_ARGS.name==null?  System.getProperty("user.name") : OPT_ARGS.name;
    if (OPT_ARGS.nodes != null) {
      NODES_FILE = OPT_ARGS.nodes;
      MULTICAST_ENABLED = false;
    }
      
    // Multi-cast ports are in the range E1.00.00.00 to EF.FF.FF.FF
    int hash = NAME.hashCode()&0x7fffffff;
    int port = (hash % (0xF0000000-0xE1000000))+0xE1000000;
    byte[] ip = new byte[4];
    for( int i=0; i<4; i++ )
      ip[i] = (byte)(port>>>((3-i)<<3));
    try {
      CLOUD_MULTICAST_GROUP = InetAddress.getByAddress(ip);
    } catch( UnknownHostException e ) { throw new Error(e); }
    CLOUD_MULTICAST_PORT = (port>>>16);

    if( !MULTICAST_ENABLED ) {
      try {
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(NODES_FILE)));
        String strLine = null;
        while ((strLine = br.readLine()) != null)   {
          final String[] ss = strLine.split("[\\s:]");
          final InetAddress inet = InetAddress.getByName(ss[0]);
          if( !(inet instanceof Inet4Address) )
            Log.die("Only IP4 addresses allowed.");
          try {
            final int port2 = Integer.decode(ss[1]);
            NODES.add(H2ONode.intern(inet,port2));
          } catch( NumberFormatException nfe ) {
            Log.die("Invalid port #: "+ss[1]);
          }
        }
      } catch( Exception e ) {  Log.die(e.toString()); }
    }
  }

  // Test if this port is available
  static boolean test_port( int port ) {
    boolean res = true;
    ServerSocket ss = null;
    try { 
      ss = new ServerSocket(port);
    } catch( IOException se ) {
      res = false;
    } finally {
      if( ss != null ) 
        try { ss.close(); } catch( IOException se ) { }
    }
    return res;
  }

  static void initializePersistence() {
    if (OPT_ARGS.hdfs!=null) Hdfs.initialize();
    // Note that we get a LocalPersist EVEN IF HDFS is out there... for our
    // internal non-HDFS keys.
    PersistIce.initialize();
  }

}
