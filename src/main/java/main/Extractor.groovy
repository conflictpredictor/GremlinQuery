package main
import org.eclipse.jgit.api.CleanCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RenameBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.lib.ObjectId;

import com.tinkerpop.gremlin.groovy.Gremlin;

import scala.util.control.Exception.Catch;
import util.ChkoutCmd
//import util.RecursiveFileList

class Extractor {

	// the url of the repository
	private String remoteUrl

	// the directory to clone in
	private String repositoryDir

	// the work folder
	private String projectsDirectory

	// the list of all merge commits
	private ArrayList<MergeCommit> listMergeCommit

	// the referred project
	private GremlinProject project

	// the git repository
	private Git git

	// conflicts counter
	private def CONFLICTS

	private ArrayList<String> missingUnknown

	ArrayList<String> revisionFiles

	public Extractor(GremlinProject project, String projectsDirectory){
		this.project			= project
		this.listMergeCommit 	= this.project.listMergeCommit
		this.remoteUrl 			= this.project.url
		this.projectsDirectory	= projectsDirectory + File.separator
		this.repositoryDir		= this.projectsDirectory + this.project.name + "/git"
		this.CONFLICTS 			= 0
		this.missingUnknown = new ArrayList<MergeCommit>()
		this.removeOldRevisionFile()
		this.revisionFiles = new Hashtable<String, Integer>()
		this.setup()
	}

	public void removeOldRevisionFile(){
		def out = new File("ResultData" + File.separator + this.project.name + File.separator + 'RevisionsFiles.csv')
		out.delete()

	}

	def cloneRepository(){
		// prepare a new folder for the cloned repository
		File gitWorkDir = new File(repositoryDir)
		gitWorkDir.mkdirs()

		// then clone
		println "Cloning from " + remoteUrl + " to " + gitWorkDir + "..."
		Git.cloneRepository()
				.setURI(remoteUrl)
				.setDirectory(gitWorkDir)
				.call();

		// now open the created repository
		FileRepositoryBuilder builder = new FileRepositoryBuilder()
		Repository repository = builder.setGitDir(gitWorkDir)
				.readEnvironment() // scan environment GIT_* variables
				.findGitDir() // scan up the file system tree
				.build();

		println "Having repository: " + repository.getDirectory()
		repository.close()

	}

	def Git openRepository() {
		try {
			File gitWorkDir = new File(repositoryDir);
			Git git = Git.open(gitWorkDir);
			Repository repository = git.getRepository()
			this.renameMainBranchIfNeeded(repository)
			return git
		} catch(org.eclipse.jgit.errors.RepositoryNotFoundException e){
			this.cloneRepository()
			this.openRepository()
		}
	}

	def listAllBranches() {
		List<Ref> refs = this.git.branchList().call();
		for (Ref ref : refs) {
			println "Branch-Before: " + ref + " " + ref.getName() + " " + ref.getObjectId().getName();
		}
	}

	def checkoutMasterBranch() {
		ChkoutCmd chkcmd = new ChkoutCmd(this.git.getRepository());
		//change here if project does not contain a master branch
		chkcmd.setName("refs/heads/master")
		chkcmd.setForce(true)
		Ref checkoutResult = chkcmd.call()
		println "Checked out branch sucessfully: " + checkoutResult.getName()
	}

	def Ref checkoutAndCreateBranch(String branchName, String commit){
		ChkoutCmd chkcmd = new ChkoutCmd(this.git.getRepository());
		chkcmd.setName(branchName)
		chkcmd.setStartPoint(commit)
		chkcmd.setCreateBranch(true)
		chkcmd.setForce(true);
		Ref checkoutResult = chkcmd.call()
		println "Checked out and created branch sucessfully: " + checkoutResult.getName()

		return checkoutResult
	}

	def deleteBranch(String branchName) {
		this.git.branchDelete()
				.setBranchNames(branchName)
				.setForce(true)
				.call()
	}

	def resetCommand(git, ref){
		ResetCommand resetCommand = git.reset()
		resetCommand.setMode(ResetType.HARD)
		resetCommand.setRef(ref)
		Ref resetResult = resetCommand.call()
		println "Reseted sucessfully to: " + resetResult.getName()
	}

	def runAllFiles(parent1, parent2) {
		// folder of the revisions being tested
		def allRevFolder = this.projectsDirectory + this.project.name + "/revisions/rev_" + parent1.substring(0, 5) + "_" + parent2.substring(0, 5)
		try{
			// opening the working directory
			this.git = openRepository();
			// git reset --hard SHA1_1
			this.resetCommand(this.git, parent1)
			// copy files for parent1 revision
			def destinationDir = allRevFolder + "/rev_left_" + parent1.substring(0, 5)
			this.copyFiles(this.repositoryDir, destinationDir, "")
			// git clean -f
			CleanCommand cleanCommandgit = this.git.clean()
			cleanCommandgit.call()
			// git checkout -b new SHA1_2
			def refNew = checkoutAndCreateBranch("new", parent2)
			// copy files for parent2 revision
			destinationDir = allRevFolder + "/rev_right_" + parent2.substring(0, 5)
			def excludeDir	= "**/" + allRevFolder + "/**"
			this.copyFiles(this.repositoryDir, destinationDir, excludeDir)
			// git checkout master
			checkoutMasterBranch()
			// git merge new
			MergeCommand mergeCommand = this.git.merge()
			mergeCommand.include(refNew)
			MergeResult res = mergeCommand.call()
			if (res.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)){
				CONFLICTS = CONFLICTS + 1
				println "Revision Base: " + res.getBase().toString()
				println "Conflitcts: " + res.getConflicts().toString()
				printConflicts(res)
				// git reset --hard BASE
				def revBase = (res.getBase().toString()).split()[1]
				this.resetCommand(this.git, revBase)
				// copy files for base revision
				destinationDir = allRevFolder + "/rev_base_" + revBase.substring(0, 5)
				this.copyFiles(this.repositoryDir, destinationDir, excludeDir)
				// the input revisions listed in a file
				this.writeRevisionsFile(parent1.substring(0, 5), parent2.substring(0, 5), revBase.substring(0, 5), allRevFolder)
			} else {
				// keeping only the conflicting revisions
				this.deleteFiles(allRevFolder)
			}
			// avoiding references issues
			this.deleteBranch("new")
		} catch(org.eclipse.jgit.api.errors.CheckoutConflictException e){
			println "ERROR: " + e
			// reseting
			this.deleteFiles(allRevFolder)
			this.restoreGitRepository()
			println "Trying again..."
		} finally {
			println "Closing git repository..."
			// closing git repository
			this.git.getRepository().close()
		}
	}

	def runOnlyConflicts(parent1, parent2) {
		// folder of the revisions being tested
		def allRevFolder = this.projectsDirectory + this.project.name + "/revisions/rev_" + parent1.substring(0, 5) + "_" + parent2.substring(0, 5)
		try{
			// opening the working directory
			this.git = openRepository();
			// git reset --hard SHA1_1
			this.resetCommand(this.git, parent1)
			// git clean -f
			CleanCommand cleanCommandgit = this.git.clean()
			cleanCommandgit.call()
			// git checkout -b new SHA1_2
			def refNew = checkoutAndCreateBranch("new", parent2)
			// git checkout master
			checkoutMasterBranch()
			// git merge new
			MergeCommand mergeCommand = this.git.merge()
			mergeCommand.include(refNew)
			MergeResult res = mergeCommand.call()
			if (res.getBase() != null && res.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)){
				println "Revision Base: " + res.getBase().toString()
				println "Conflitcts: " + res.getConflicts().toString()
				def allConflicts = printConflicts(res)
				this.deleteBranch("new")
				this.git.getRepository().close()
				this.moveConflictingFiles(parent1, parent2, allConflicts)
			}
			// avoiding references issues
			this.deleteBranch("new")

		} catch(org.eclipse.jgit.api.errors.CheckoutConflictException e){
			println "ERROR: " + e
			// reseting
			this.deleteFiles(allRevFolder)
			this.restoreGitRepository()
		}catch(org.eclipse.jgit.api.errors.JGitInternalException f){
			println "ERROR: " + f
			// reseting
			this.deleteFiles(allRevFolder)
			this.restoreGitRepository()
		} catch(org.eclipse.jgit.dircache.InvalidPathException g){
			println "ERROR: " + g
			// reseting
			this.deleteFiles(allRevFolder)
			this.restoreGitRepository()
		} catch(org.eclipse.jgit.api.errors.RefNotFoundException h){
			println "ERROR: " + h
			// reseting
			this.deleteFiles(allRevFolder)
			this.restoreGitRepository()
		} catch(java.lang.NullPointerException i){
			println "ERROR: " + i
			// reseting
			this.deleteFiles(allRevFolder)
			this.restoreGitRepository()
		} finally {
			// closing git repository
			this.git.getRepository().close()
		}
	}

	def moveConflictingFiles(parent1, parent2, allConflicts) throws org.eclipse.jgit.api.errors.CheckoutConflictException,
			org.eclipse.jgit.api.errors.JGitInternalException,
			org.eclipse.jgit.dircache.InvalidPathException,
			org.eclipse.jgit.api.errors.RefNotFoundException,
	java.lang.NullPointerException  {

		// folder of the revisions being tested
		def allRevFolder = this.projectsDirectory + this.project.name + "/revisions/rev_" + parent1.substring(0, 5) + "_" + parent2.substring(0, 5)
		//try{
		// opening the working directory
		this.git = openRepository();
		// git reset --hard SHA1_1
		this.resetCommand(this.git, parent1)
		// copy files for parent1 revision
		def destinationDir = allRevFolder + "/rev_left_" + parent1.substring(0, 5)
		this.copyFiles(this.repositoryDir, destinationDir, allConflicts)
		// git clean -f
		CleanCommand cleanCommandgit = this.git.clean()
		cleanCommandgit.call()
		// git checkout -b new SHA1_2
		def refNew = checkoutAndCreateBranch("new", parent2)
		// copy files for parent2 revision
		destinationDir = allRevFolder + "/rev_right_" + parent2.substring(0, 5)
		this.copyFiles(this.repositoryDir, destinationDir, allConflicts)
		// git checkout master
		checkoutMasterBranch()
		// git merge new
		MergeCommand mergeCommand = this.git.merge()
		mergeCommand.include(refNew)
		MergeResult res = mergeCommand.call()
		if (res.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)){
			// git reset --hard BASE
			def revBase = (res.getBase().toString()).split()[1]
			this.resetCommand(this.git, revBase)
			// copy files for base revision
			destinationDir = allRevFolder + "/rev_base_" + revBase.substring(0, 5)
			this.copyFiles(this.repositoryDir, destinationDir, allConflicts)
			// the input revisions listed in a file
			this.writeRevisionsFile(parent1.substring(0, 5), parent2.substring(0, 5), revBase.substring(0, 5), allRevFolder)
		}
		// avoiding references issues
		this.deleteBranch("new")

		CONFLICTS = CONFLICTS + 1
	}

	def countConflicts(parent1, parent2){
		try{
			// opening the working directory
			this.git = openRepository();
			// git reset --hard SHA1_1
			this.resetCommand(this.git, parent1)
			// git clean -f
			CleanCommand cleanCommandgit = this.git.clean()
			cleanCommandgit.call()
			// git checkout -b new SHA1_2
			def refNew = checkoutAndCreateBranch("new", parent2)
			// git checkout master
			checkoutMasterBranch()
			// git merge new
			MergeCommand mergeCommand = this.git.merge()
			mergeCommand.include(refNew)
			MergeResult res = mergeCommand.call()
			if (res.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)){
				println "Revision Base: " + res.getBase().toString()
				println "Conflitcts: " + res.getConflicts().toString()
				printConflicts(res)
				CONFLICTS = CONFLICTS + 1
			}
			// avoiding references issues
			this.deleteBranch("new")

		} catch(org.eclipse.jgit.api.errors.CheckoutConflictException e){
			println "ERROR: " + e
			// reseting
			this.restoreGitRepository()
		}catch(org.eclipse.jgit.api.errors.JGitInternalException f){
			println "ERROR: " + f
			// reseting
			this.restoreGitRepository()
		} catch(org.eclipse.jgit.dircache.InvalidPathException g){
			println "ERROR: " + g
			// reseting
			this.restoreGitRepository()
		} catch(org.eclipse.jgit.api.errors.RefNotFoundException h){
			println "ERROR: " + h
			// reseting
			this.restoreGitRepository()
		} catch(java.lang.NullPointerException i){
			println "ERROR: " + i
			// reseting
			this.restoreGitRepository()
		} finally {
			// closing git repository
			this.git.getRepository().close()
		}
	}

	def printConflicts(MergeResult res) {
		Map allConflicts = res.getConflicts();
		def listConflicts = []
		for (String path : allConflicts.keySet()) {
			int[][] c = allConflicts.get(path);
			println "Conflicts in file " + path
			for (int i = 0; i < c.length; ++i) {
				println " Conflict #" + i
				for (int j = 0; j < (c[i].length) - 1; ++j) {
					if (c[i][j] >= 0)
						println" Chunk for " + res.getMergedCommits()[j] + " starts on line #" + c[i][j];
				}
			}
			listConflicts.add(path)
		}
		return listConflicts
	}

	def copyFiles(String sourceDir, String destinationDir, String excludeDir){
		new AntBuilder().copy(todir: destinationDir) {
			fileset(dir: sourceDir){
				exclude(name:excludeDir)
			}
		}
	}

	def copyFiles(String sourceDir, String destinationDir, ArrayList<String> listConflicts){
		AntBuilder ant = new AntBuilder()
		listConflicts.each {
			def folder = it.split("/")
			def fileName = folder[(folder.size()-1)]
			if(fileName.contains(".")){
				def fileNameSplitted = fileName.split("\\.")
				def fileExt = fileName.split("\\.")[fileNameSplitted.size() -1]
				if(canCopy(fileExt)){
					folder = destinationDir + "/" + (Arrays.copyOfRange(folder, 0, folder.size()-1)).join("/")
					String file = "**/" + it
					ant.mkdir(dir:folder)
					ant.copy(todir: destinationDir) {
						fileset(dir: sourceDir){
							include(name:file)
						}
					}
				}
			}
		}
	}

	def boolean canCopy(String fileName){
		boolean can = false
		if(fileName.equals("java") || fileName.equals("py") || fileName.equals("cs")){
			can = true
		}
		return can
	}

	def deleteFiles(String dir){
		(new AntBuilder()).delete(dir:dir,failonerror:false)
	}

	private String writeRevisionsFile(String leftRev, String rightRev, String baseRev, String dir){
		String filePath = ''
		try{
			filePath = dir + "/rev_" + leftRev + "-" + rightRev + ".revisions"
			def out = new File(filePath)


			// deleting old files if it exists
			out.delete()
			out = new File(filePath)
			def row = "rev_left_" + leftRev
			out.append row
			out.append '\n'
			row = "rev_base_" + baseRev
			out.append row
			out.append '\n'
			row = "rev_right_" + rightRev
			out.append row
			out.append '\n'

		}catch(Exception e){} //The file is not created, and just return
		return filePath
	}

	public String getRevisionFile(){
		String current = new java.io.File( "." ).getCanonicalPath();
		String filePath = current + File.separator + "ResultData" + File.separator + this.project.name + File.separator +'RevisionsFiles.csv'
		return filePath
	}

	def setup(){
		println "Setupping..."
		// keeping a backup dir
		this.openRepository()
		new AntBuilder().copy(todir: this.projectsDirectory + "/temp/"+this.project.name+"/git") {fileset(dir: this.projectsDirectory+this.project.name+"/git", defaultExcludes: false){}}
		println "----------------------"
	}

	def restoreGitRepository(){
		println "Restoring Git repository..."
		this.git.getRepository().close()
		// restoring the backup dir
		new File(this.projectsDirectory+this.project.name+"/git").deleteDir()
		new AntBuilder().copy(todir:this.projectsDirectory+this.project.name+"/git") {fileset(dir:this.projectsDirectory + "/temp/" + this.project.name+"/git" , defaultExcludes: false){}}
	}

	public 	String extractCommit(MergeCommit mergeCommit){
			String revisionFile = ''
			// the commits to checkout
			def SHA_1 = mergeCommit.parent1
			def SHA_2 = mergeCommit.parent2

			//FUTURE VARIATION POINT
			//if you wan't to catch only the false positives use these
			//this.runAllFiles(SHA_1, SHA_2)
			//elseif you wan't to download all merges


			def ancestorSHA = this.findCommonAncestor(SHA_1, SHA_2)
			if(ancestorSHA != null){
			revisionFile = this.downloadAllFiles(SHA_1, SHA_2, ancestorSHA)
			this.printRevisionFiles(revisionFile)
			}else{
				println('commit sha:' + mergeCommit.getSha() + ' returned null on common ancestor search.')
			}

		return revisionFile
	}

	private void printRevisionFiles(String s){
		String temp = "ResultData" + File.separator + this.project.name +
				File.separator + 'RevisionsFiles.csv'
		File file = new File(temp)
		
		String line = s + '\n'
		file.append(line)

	}
	
	private String downloadAllFiles(parent1, parent2, ancestor) {
		// folder of the revisions being tested
		def allRevFolder = this.projectsDirectory + this.project.name + "/revisions/rev_" + parent1.substring(0, 5) + "_" + parent2.substring(0, 5)
		String result = ''
		try{
			// opening the working directory
			this.git = openRepository();
			// git reset --hard SHA1_1
			this.resetCommand(this.git, parent1)

			// copy files for parent1 revision
			def destinationDir = allRevFolder + "/rev_left_" + parent1.substring(0, 5)

			this.copyFiles(this.repositoryDir, destinationDir, "")
			//def rec = new RecursiveFileList()
			//rec.removeFiles(new File(destinationDir))

			// git clean -f
			CleanCommand cleanCommandgit = this.git.clean()
			cleanCommandgit.call()

			// git checkout -b new SHA1_2
			def refNew = checkoutAndCreateBranch("new", parent2)
			// copy files for parent2 revision
			destinationDir = allRevFolder + "/rev_right_" + parent2.substring(0, 5)
			def excludeDir	   = "**/" + allRevFolder + "/**"

			this.copyFiles(this.repositoryDir, destinationDir, excludeDir)
			rec.removeFiles(new File(destinationDir))


			cleanCommandgit = this.git.clean()
			cleanCommandgit.call()
			// git checkout -b ancestor ANCESTOR

			checkoutMasterBranch()
			cleanCommandgit = this.git.clean()
			cleanCommandgit.call()

			def refAncestor = checkoutAndCreateBranch("ancestor", ancestor)
			// copy files for ancestor revision
			destinationDir = allRevFolder + "/rev_base_" + ancestor.substring(0, 5)
			excludeDir	   = "**/" + allRevFolder + "/**"

			this.copyFiles(this.repositoryDir, destinationDir, excludeDir)
			rec.removeFiles(new File(destinationDir))

			result = this.writeRevisionsFile(parent1.substring(0, 5), parent2.substring(0, 5),
					ancestor.substring(0, 5), allRevFolder)
			// avoiding references issues
			checkoutMasterBranch()
			this.deleteBranch("ancestor")
			this.deleteBranch("new")

		} catch(org.eclipse.jgit.api.errors.CheckoutConflictException e){
			println "ERROR: " + e
			// reseting
			this.restoreGitRepository()
		}catch(org.eclipse.jgit.api.errors.JGitInternalException f){
			println "ERROR: " + f
			// reseting
			this.restoreGitRepository()
		} catch(org.eclipse.jgit.dircache.InvalidPathException g){
			println "ERROR: " + g
			// reseting
			this.restoreGitRepository()
		} catch(org.eclipse.jgit.api.errors.RefNotFoundException h){
			println "ERROR: " + h
			// reseting
			this.restoreGitRepository()
		} catch(java.lang.NullPointerException i){
			println "ERROR: " + i
			// reseting
			this.restoreGitRepository()
		} finally {
			// closing git repository
			this.git.getRepository().close()
		}
		return result
	}

	String findCommonAncestor(parent1, parent2){

		String ancestor = null
		this.git = openRepository()

		RevWalk walk = new RevWalk(this.git.getRepository())
		walk.setRetainBody(false)
		walk.setRevFilter(RevFilter.MERGE_BASE)
		walk.reset()

		ObjectId shaParent1 = ObjectId.fromString(parent1)
		ObjectId shaParent2 = ObjectId.fromString(parent2)
		ObjectId commonAncestor = null

		try {
			walk.markStart(walk.parseCommit(shaParent1))
			walk.markStart(walk.parseCommit(shaParent2))
			commonAncestor = walk.next()
		} catch (Exception e) {
			e.printStackTrace()
		}

		if(commonAncestor != null){
			ancestor = commonAncestor.toString()substring(7, 47)
			println('The common ancestor is: ' + ancestor)
		}

		this.git.getRepository().close()
		return ancestor
	}

	def private renameMainBranchIfNeeded(Repository repository){
		def branchName = repository.getBranch();
		if(branchName != "master"){
			RenameBranchCommand renameCommand = new RenameBranchCommand(repository);
			renameCommand.setNewName("master")
			renameCommand.call()
		}
	}


	static void main (String[] args){
		//		//testing
		//		MergeCommit mc = new MergeCommit()
		//		mc.sha 		= "b4df7ee0b908f16cce2f7c819927fe5deb8cb6b9"
		//		mc.parent1  = "fd21ef43df591ef86ad899d96d2d6a821ebb342d"
		//		mc.parent2  = "576c6b3966cb85353ba874f6c9f2e65c4a89c70b"
		//
		//		ArrayList<MergeCommit> lm = new ArrayList<MergeCommit>()
		//		lm.add(mc)
		//
		GremlinProject p = new GremlinProject()
		p.name = "orientdb"
		p.url = "https://github.com/orientechnologies/orientdb.git"
		p.graph = "C:/Users/Guilherme/Documents/workspace/gitminer-master/gitminer-master/graph.db_30-10"

		Extractor ex = new Extractor(p,"/Users/paolaaccioly/Documents/Doutorado/temp/orientdb")
		String base = ex.findCommonAncestor("98f95d085f0026055792c70295787e38df99e654", "9aa13e5348e9e05812ddddf55bac7a0eeb39598c")
		println base


	}
}
