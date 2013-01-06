
## Dataset created from this:
#
# from sklearn.datasets import make_hastie_10_2
# import numpy as np
# i = 1000000
# f = 10
# (X,y) = make_hastie_10_2(n_samples=i,random_state=None)
# y.shape = (i,1)
# Y = np.hstack((X,y))
# np.savetxt('./1mx' + str(f) + '_hastie_10_2.data', Y, delimiter=',', fmt='%.2f');

import os, json, unittest, time, shutil, sys
sys.path.extend(['.','..','py'])
import h2o, h2o_cmd, h2o_glm, h2o_util
import copy

def glm_doit(self, csvFilename, csvPathname, timeoutSecs=30):
    print "\nStarting parse of", csvFilename
    parseKey = h2o_cmd.parseFile(csvPathname=csvPathname, key2=csvFilename, timeoutSecs=10)
    Y = "9"
    X = ""
    # Took xval out, because GLM doesn't include xval time and it's slow
    # wanted to compare GLM time to my measured time
    # FIX! GLMGrid doesn't default to case=NaN

    # nan = float('inf')/float('inf') 
    # kwargs = {'X': X, 'Y':  Y, 'case': float('nan')}

    # FIX! what are the legal values for case?
    # {u'error': u'Argument case error: Value -1.0 is not between 0.0 and 1.0 (inclusive)'} 
    kwargs = {'X': X, 'Y':  Y, 'case': 1, 'destination_key': 'gg'}

    start = time.time() 
    print "\nStarting GLMGrid of", csvFilename
    glmGridResult = h2o_cmd.runGLMGridOnly(parseKey=parseKey, timeoutSecs=timeoutSecs, **kwargs)
    print "GLMGrid in",  (time.time() - start), "secs (python)"
    h2o.verboseprint("glmGrid result:", h2o.dump_json(glmGridResult))

    destination_key = glmGridResult['destination_key']
    inspect2 = h2o_cmd.runInspect(None, destination_key)
    h2o.verboseprint("Inspect of destination_key", 
        destination_key,":\n", h2o.dump_json(inspect2))

    ### inspect1 = h2o_cmd.runInspect(None, 'gg')

    # h2o_glm.simpleCheckGLM(self, glm, 7, **kwargs)

    # compare this glm to the first one. since the files are replications, the results
    # should be similar?
    # GLMModel = glm['GLMModel']
    # GLMModel = glm['GLMModel']
    # validationsList = glm['GLMModel']['validations']
    # validations = validationsList[0]
    # validations['err']

    # if self.validations1:
    #     h2o_glm.compareToFirstGlm(self, 'err', validations, self.validations1)
    # else:
    #     self.validations1 = copy.deepcopy(validations)


class Basic(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        h2o.build_cloud(1)
        global SYNDATASETS_DIR
        SYNDATASETS_DIR = h2o.make_syn_dir()

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()


    validations1 = {}

    def test_A_1mx10_hastie_10_2(self):
        # gunzip it and cat it to create 2x and 4x replications in SYNDATASETS_DIR
        # FIX! eventually we'll compare the 1x, 2x and 4x results like we do
        # in other tests. (catdata?)
        csvFilename = "1mx10_hastie_10_2.data.gz"
        csvPathname = h2o.find_dataset('logreg' + '/' + csvFilename)
        glm_doit(self,csvFilename, csvPathname, timeoutSecs=30)

        filename1x = "hastie_1x.data"
        pathname1x = SYNDATASETS_DIR + '/' + filename1x
        h2o_util.file_gunzip(csvPathname, pathname1x)

        filename2x = "hastie_2x.data"
        pathname2x = SYNDATASETS_DIR + '/' + filename2x
        h2o_util.file_cat(pathname1x,pathname1x,pathname2x)
        glm_doit(self,filename2x, pathname2x, timeoutSecs=45)

        filename4x = "hastie_4x.data"
        pathname4x = SYNDATASETS_DIR + '/' + filename4x
        h2o_util.file_cat(pathname2x,pathname2x,pathname4x)
        
        print "Iterating 3 times on this last one for perf compare"
        for i in range(3):
            print "\nTrial #", i, "of", filename4x
            glm_doit(self,filename4x, pathname4x, timeoutSecs=60)

if __name__ == '__main__':
    h2o.unit_main()