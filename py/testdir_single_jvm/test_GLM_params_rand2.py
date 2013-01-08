import unittest
import random, sys, time
sys.path.extend(['.','..','py'])
import json

import h2o, h2o_cmd
import h2o_glm

# none is illegal for threshold
# always run with xval, to make sure we get the trainingErrorDetails
# FIX! we'll have to do something for gaussian. It doesn't return the ted keys below

paramDict = {
    'Y': [54],
    'X': ['0:3','14:17','5:10',0,1,15,33],
    'glm_-X': [None,'20,28,40:53'],
    'family': [None, 'gaussian', 'binomial', 'poisson', 'gamma'],
    'xval': [2,3,4,9],
    'threshold': [0.1, 0.5, 0.7, 0.9],
    # 'norm': [None,'L1', 'L2'],
    # always need L1 or L2? to avoid Gram Matrix SPD
    'norm': ['L1', 'L2'],
    'glm_lambda': [None, 1e-4,1,10,1e4],
    'rho': [None, 1e-4,1,10,1e4],
    'alpha': [None, 1e-4,1,10,1e4],
    # new?
    'betaEps': [None, 0.0001],
    'case': ['NaN'],
    # inverse and log causing problems
    # 'link': [None, 'logit','identity', 'log', 'inverse'],
    # 'link': [None, 'logit','identity'],
    'maxIter': [None, 10],
    'weight': [None, 1, 2, 4],

    }

class Basic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        h2o.build_cloud(node_count=1)

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_loop_random_param_covtype(self):

        # csvPathname = h2o.find_dataset('UCI/UCI-large/covtype/covtype.data')
        csvPathname = h2o.find_file('smalldata/covtype/covtype.20k.data')
        parseKey = h2o_cmd.parseFile(csvPathname=csvPathname)

        # for determinism, I guess we should spit out the seed?
        # random.seed(SEED)
        SEED = random.randint(0, sys.maxint)
        # if you have to force to redo a test
        # SEED =
        random.seed(SEED)
        print "\nUsing random seed:", SEED
        for trial in range(20):
            # default
            colX = 0
            # form random selections of RF parameters
            # always need Y=54. and always need some xval (which can be overwritten)
            # with a different choice. we need the xval to get the error details
            # in the json(below)
            kwargs = {'Y': 54, 'norm': 'L2'}
            randomGroupSize = random.randint(1,len(paramDict))
            for i in range(randomGroupSize):
                randomKey = random.choice(paramDict.keys())
                randomV = paramDict[randomKey]
                randomValue = random.choice(randomV)
                kwargs[randomKey] = randomValue

                if (randomKey=='X'):
                    # keep track of what column we're picking
                    # don't track a column if we're using a range (range had a GLM bug, so have to test)
                    # the shared check code knows to ignore colX if None, now.
                    if ':' in randomValue:
                        colX = None
                    else:
                        colX = randomValue

            start = time.time()
            glm = h2o_cmd.runGLMOnly(timeoutSecs=70, parseKey=parseKey, **kwargs)
            # pass the kwargs with all the params, so we know what we asked for!
            h2o_glm.simpleCheckGLM(self, glm, colX, **kwargs)
            print "glm end on ", csvPathname, 'took', time.time() - start, 'seconds'
            print "Trial #", trial, "completed\n"

if __name__ == '__main__':
    h2o.unit_main()
