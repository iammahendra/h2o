
package analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** Class capable of building random forests.
 *
 * @author peta
 */
public abstract class RFBuilder {
  
  /** Creates the statistics for the node under construction. The statistics are
   * based on the list of selected columns. 
   * 
   * @param node
   * @param columns 
   */
  protected abstract void createNodeStatistics(ProtoNode node, int[] columns);
   
  protected abstract int getNumberOfNodeFeatures(ProtoNode node, 
      ProtoTree tree);
  
  // implementation ------------------------------------------------------------
  
  private final long seed;
  private final Random random;
  
  // all trees under construction
  public ProtoTree[] trees;
  
  NodePartitioning nodePartitioning_;
  
  private final DataAdapter data_;
  
  
  protected RFBuilder(long seed, DataAdapter data) {
    this.seed = seed;
    random = new Random(seed);
    data_ = data;
  }
  // node under construction ---------------------------------------------------
  
  /** Describes the node that is under construction. The node has a list of all
   * statistics that must be computed for the node. 
   */
  public static class ProtoNode {
    
    long[] statisticsData_ = null;
    
    
    // list of all statistics that must be computed for the node
    protected final ArrayList<Statistic> statistics_ = new ArrayList();
    
    /** Adds the given statistic to the node. All statistics associated with a
     * node under construction are computed for each row.
     * 
     * @param stat 
     */
    public void addStatistic(Statistic stat) {
      statistics_.add(stat);
    }

    
    /** Initializes the storage space required for the statistics of the given
     * node. 
     */
    public void initializeStatistics() {
      int size = 0;
      for (Statistic s: statistics_) {
        size += s.dataSize();
        size = (size + 7) & -8; // round to multiple of 8
      }
      statisticsData_ = new long[size];
    }
    
    
    /** Returns the normal node that should be created from the node under
     * construction. Determines the best statistic for the node based on their
     * ordering and creates its classifier which is in turn used to produce
     * the proper node. 
     * 
     * @return 
     */
    DecisionTree.INode createTreeNode() {
      assert (statistics_.size()!=0);
      Statistic best = statistics_.get(0);
      int bestOffset = 0;
      double bestFitness = best.fitness(statisticsData_,bestOffset);
      int offset = 0+best.dataSize();
      for (int i = 1; i< statistics_.size(); ++i) {
        double f = statistics_.get(i).fitness(statisticsData_,offset);
        if (f>bestFitness) {
          best = statistics_.get(i);
          bestOffset = offset;
          bestFitness = f;
        }
        offset += statistics_.get(i).dataSize();
      }
      Classifier nc = best.createClassifier(statisticsData_,bestOffset);
      if (nc instanceof Classifier.Const) {
        return new DecisionTree.LeafNode(nc.classify(null));
      } else {      
        return new DecisionTree.Node(nc);
      }
    }
    
    /** Computes all the statistics of the node on given row. The row is added
     * as a datapoint to all the statistics available for the node. 
     * 
     * @param row DataPoint to be added.
     */
    void computeStatistics(DataAdapter row) {
      int offset = 0;
      for (Statistic stat : statistics_) {
        stat.addDataPoint(row,statisticsData_, offset);
        offset += (stat.dataSize() + 7) & -8; // round to multiple of 8
      }
    }

    /** Returns the array of n randomly selected numbers from 0 to columns
     * exclusively using the random generator provided.
     * 
     * @param features
     * @param columns
     * @param random
     * @return 
     */ 
    int[] getColumnArray(int features, int columns, Random random) {
      int[] cols = new int[columns];
      for (int i = 0; i<cols.length; ++i)
        cols[i] = i;
      for (int i = 0; i<features; ++i) {
        int x = random.nextInt(cols.length-i)+i;
        if (i!=x) { // swap the elements
          int s = cols[i];
          cols[i] = cols[x];
          cols[x] = s;
        }
      }
      return Arrays.copyOf(cols,features);
    }    
  }
  

  // tree under construction ---------------------------------------------------
  
  /** Decision tree currently under construction. Contains both the already
   * finished parts of the decision tree and the level that is currently under
   * construction. 
   */
  public class ProtoTree {
    
    public DecisionTree.INode[] lastLevelNodes_;
    
    public int[] lastLevelNodeOffsets_;
    
    public ProtoNode[] currentLevelNodes_;
    
    public int currentLevel_ = -1;
    
    public DecisionTree.INode root_ = null;
    
    // random generator unique to the tree. 
    Random rnd = null;
    
    // random seed used to generate the random, therefore we can always reset it
    final long seed;
    
    /** Creates the tree under construction.
     * 
     * Initializes the seed from the parent
     */
    public ProtoTree() {
      this.seed = random.nextLong();
    }
    
    // move to tree next level logic -------------------------------------------
    
    // initializes the tree under construction to compute the root
    protected final int updateFromLevel0() {
      root_ =  currentLevelNodes_[0].createTreeNode();
      lastLevelNodes_ = new DecisionTree.INode[] { root_ };
      lastLevelNodeOffsets_ = new int[] { 0 };
      return root_.numClasses() == 1 ? 0 : root_.numClasses();
    }
    
    // we are a level with old nodes. What must be done is:
    // - convert the nodes under construction to normal nodes and add them
    //   to their parents
    // - fill in the node offsets appropriately
    // - update the lastLevelNodes appropriately
    protected final int updateToNextLevel() {
      int newNodes = 0;
      // list of new level nodes
      DecisionTree.INode[] levelNodes = new DecisionTree.INode[currentLevelNodes_.length];
      lastLevelNodeOffsets_= new int[currentLevelNodes_.length];
      int nodeIndex = 0; // to which node we are adding
      int subnodeIndex = 0; // which subtree are we setting
      for (int i = 0; i < currentLevelNodes_.length; ++i) {
        // make sure that nodeIndex and subnodeIndex are set properly
        while (true) {
          if (lastLevelNodes_[nodeIndex].numClasses()<=subnodeIndex) {
            ++nodeIndex; // move to next node
            subnodeIndex = 0; // reset subnode index
          } else if (lastLevelNodes_[nodeIndex].numClasses()==1) {
            ++nodeIndex; 
            assert (subnodeIndex == 0);
          } else {
            break;
          }
        }
        DecisionTree.INode n = currentLevelNodes_[i].createTreeNode();
        // fill in the new last level nodes and offsets
        levelNodes[i] = n;
        lastLevelNodeOffsets_[i] = newNodes;
        // if it is not a leaf node, add the number of children to the nodes
        // to be constructed
        if (n.numClasses()>1)
          newNodes += n.numClasses();
        // store the node to its proper position and increment the subnode
        // index
        ((DecisionTree.Node)lastLevelNodes_[nodeIndex]).setSubtree(subnodeIndex,n);
        ++subnodeIndex;
      }
      // change the lastLevelNodes to the levelNodes computed
      lastLevelNodes_ = levelNodes;
      // return the amount of nodes to be created
      return newNodes;
    }
    
    // Builds the numNodes of nodesUnderConstruction. These nodes are then
    // initialized to produce the 
    protected final void buildNodesUnderConstruction(int numNodes) {
      // build the new nodes under construction
      // if there are no new nodes to build, set current nodes to null
      if (numNodes == 0) {
        currentLevelNodes_ = null;
      // otherwise build the new nodes
      } else {
        currentLevelNodes_ = new ProtoNode[numNodes];
        for (int i = 0; i<numNodes; ++i) {
          ProtoNode n = new ProtoNode();
          createNodeStatistics(n, n.getColumnArray(getNumberOfNodeFeatures(n,this), 
              data_.numColumns(), random));
          n.initializeStatistics();
          currentLevelNodes_[i] = n;
        }        
      }
    }
    
    /** Moves the decision tree to next level. This means that all current level
     * nodes are converted to normal nodes, these are added to the trees and new
     * current level nodes are created so that their statistics can be computed.
     */
    public void createNextLevel() {
      int newNodes = 0;
      // if the current level is -1 just create the node under construction for
      // the to be root of the tree
      if (currentLevel_ == -1) {
        lastLevelNodes_ = null;
        newNodes = 1;
      // if currentLevelNodes are null, then the tree has already decided and there is
      // no point in doing anything
      } else if (currentLevelNodes_ == null) {
        lastLevelNodeOffsets_ = null;
        lastLevelNodes_ = null;
      // if we are not initializing the first level, we must convert all nodes
      // under construction to proper nodes and put them in the tree and then
      // create new nodes under construction for the next level
      } else {
        // numer of nodes to be created for the next level
        // if the current level is 0, we are dealing with the first level, store
        // as root and create children as required
        if (currentLevel_ == 0) {
          newNodes = updateFromLevel0();
        // or do the proper update, for which see the method
        } else {
          newNodes = updateToNextLevel();
        }
      }
      buildNodesUnderConstruction(newNodes);
      // reset the random generator for the rows
      rnd = new Random(this.seed);
      ++currentLevel_;
    }
    
    // get node number in new level logic --------------------------------------
    
    /** Returns the new node number for the given row. The node number is
     * calculated from the old node number and its classifier. If the oldNode
     * is -1 it means the node is no longer in the tree and should be ingnored.
     * 
     * @param row
     * @param oldNode
     * @return 
     */
    int getNodeNumber(DataAdapter row, int oldNode) {
      // if we are already -1 ignore the row completely, it has been solved
      if (oldNode == -1)
        return -1;
      // if the lastLevelNodes are not present, we are calculating root and
      // therefore all rows are node 0
      if (lastLevelNodes_ == null)
        return 0;
      // if the lastNode is leaf, do not include the row in any further tasks
      // for this tree. It has already been decided
      if (lastLevelNodes_[oldNode].numClasses() == 1)
        return -1;
      // use the classifier on the node to classify the node number in the new
      // level
      return lastLevelNodeOffsets_[oldNode]+
          ((DecisionTree.Node)lastLevelNodes_[oldNode]).classify(row);
    }
    
    // compute statistics for the node -----------------------------------------

    /** Computes the statistics for given node of the tree. Skips the process if
     * the nodeNumber is -1 indicating that the row has already been decided by
     * the tree. 
     * 
     * @param row
     * @param nodeNumber 
     */
    void computeStatistics(DataAdapter row, int nodeNumber) {
      if (nodeNumber!=-1)
        currentLevelNodes_[nodeNumber].computeStatistics(row);
    }  
  }
 
  
  /** Computes n random decision trees. 
   * 
   * TODO the trees are still stored in their TreeUnderConstruction roots. This
   * should change to some better API. 
   * 
   * @param numTrees 
   */
  public void compute(int numTrees, boolean randomizeInput) {
    nodePartitioning_ = new NodePartitioning(numTrees,data_.numRows());
    trees = new ProtoTree[numTrees];
    for (int i = 0; i<numTrees; ++i) {
      trees[i] = new ProtoTree();
      trees[i].createNextLevel();
    } 
    int i=0;
    while (true) { // for each level
      System.out.println("level " + i++);
      boolean allDone = true;      
      for (int t= 0; t < numTrees; ++t) {
        ProtoTree tree = trees[t];
        int cr = 0;
        for (int r = 0; r < data_.numRows(); ++r) {
          int node = nodePartitioning_.getNode(t,r);
          // get the randomized row, because at each level the random generator
          // is reset, we always get the same rows in the same order. 
          //
          // TODO maybe do something more efficient like swap the rows, so that
          // we can look at them sequentially rather than this
          data_.getRow(randomizeInput ? tree.rnd.nextInt(data_.numRows()) : r);
          node = tree.getNodeNumber(data_, node);
          tree.computeStatistics(data_,node);
          if (node!=-1) cr++;
          nodePartitioning_.setNode(t,r,node);
        }
        tree.createNextLevel();
        // the tree has been done, we may upgrade it to next level
        if (tree.currentLevelNodes_!=null)
          allDone = false;
      }
      if (allDone)
        break;
    }
  }
  
}


/** This is sooo dummy I am embarrassed to even talk about it;).
 * 
 * Just provides information on which row under which tree belongs to which
 * node. 
 * 
 * @author peta
 */
class NodePartitioning {
  
  final int[][] data_;

  
  public NodePartitioning(int trees, int rows) {
    data_ = new int[trees][rows];
  }
  
  public int getNode(int tree, int row) {
    return data_[tree][row];
  }
  
  public void setNode(int tree, int row, int node) {
    data_[tree][row] = node;
  }
}
