import os, json, unittest, time, shutil, sys
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd
import h2o_hosts
import h2o_browse as h2b
import h2o_import as h2i
import time, random, copy

class Basic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        h2o_hosts.build_cloud_with_hosts()

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_B_importFolder_GLM_bigger_and_bigger(self):
        # We don't drop anything from csvFilename, unlike H2O default
        # FIX! for local 0xdata, this will be different (/home/0xdiag/datasets)
        csvFilenameList = [
            'covtype200x.data',
            'covtype200x.data',
            'covtype.data',
            'covtype.data',
            'covtype20x.data',
            'covtype20x.data',
            ]

        # a browser window too, just because we can
        h2b.browseTheCloud()

        importFolderPath = '/home/0xdiag/datasets'
        h2i.setupImportFolder(None, importFolderPath)
        firstglm= {}
        for csvFilename in csvFilenameList:
            # creates csvFilename.hex from file in importFolder dir 
            parseKey = h2i.parseImportFolderFile(None, csvFilename, importFolderPath, timeoutSecs=2000)
            print csvFilename, 'parse TimeMS:', parseKey['TimeMS']
            print "Parse result['Key']:", parseKey['Key']

            # We should be able to see the parse result?
            inspect = h2o.runInspect(parseKey['Key'])
            print "\n" + csvFilename

            start = time.time()
            # can't pass lamba as kwarg because it's a python reserved word
            # FIX! just look at X=0:1 for speed, for now
            kwargs = {'Y': 54, 'X': '0:53', 'norm': "L2", 'xval': 2, 'family': "binomial"}
            glm = h2o_cmd.runGLMOnly(parseKey=parseKey, timeoutSecs=2000, **kwargs)

            # different when xvalidation is used? No trainingErrorDetails?
            h2o.verboseprint("\nglm:", glm)
            print "GLM time", glm['time']

            h2b.browseJsonHistoryAsUrlLastMatch("GLM")

            GLMModel = glm['GLMModel']
            coefficients = GLMModel['coefficients']
            validationsList = GLMModel['validations']
            validations = validationsList.pop()
            # validations['err']

            if validations1:
                h2o_glm.glmCompareToFirst(self, 'err', validations, validations1)
            else:
                validations1 = deepcopy(validations)

            if coefficients1:
                h2o_glm.glmCompareToFirst(self, '0', coefficients, coefficients1)
            else:
                coefficients1 = deepcopy(coefficients)

            sys.stdout.write('.')
            sys.stdout.flush() 

if __name__ == '__main__':
    h2o.unit_main()
