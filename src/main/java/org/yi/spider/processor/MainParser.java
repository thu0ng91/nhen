package org.yi.spider.processor;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yi.spider.constants.ConfigKey;
import org.yi.spider.constants.Constants;
import org.yi.spider.constants.GlobalConfig;
import org.yi.spider.entity.ChapterEntity;
import org.yi.spider.entity.NovelEntity;
import org.yi.spider.enums.CategoryGradeEnum;
import org.yi.spider.enums.ParamEnum;
import org.yi.spider.enums.RepairParamEnum;
import org.yi.spider.exception.BaseException;
import org.yi.spider.factory.impl.ServiceFactory;
import org.yi.spider.helper.FileHelper;
import org.yi.spider.helper.ParseHelper;
import org.yi.spider.model.CollectParam;
import org.yi.spider.model.DuoYinZi;
import org.yi.spider.model.PreNextChapter;
import org.yi.spider.model.Rule;
import org.yi.spider.service.IChapterService;
import org.yi.spider.service.IHtmlBuilder;
import org.yi.spider.service.INovelService;
import org.yi.spider.utils.HttpUtils;
import org.yi.spider.utils.ObjectUtils;
import org.yi.spider.utils.PinYinUtils;
import org.yi.spider.utils.StringUtils;

/**
 * 
 * @ClassName: ParseProcessor
 * @Description: 解析主控类
 * @author QQ
 */
public class MainParser {
	
	private static final Logger logger = LoggerFactory.getLogger(MainParser.class);
	
	private CloseableHttpClient httpClient;
	
	private CollectParam cpm;
	
	private INovelService novelService;
	
	private IChapterService chapterService;
	
	private IHtmlBuilder htmlBuilder;
	
	public MainParser(CollectParam cpm) throws Exception {
		super();
		this.httpClient = HttpUtils.buildClient(Constants.TEST_TIMEOUT);
		this.cpm = cpm;
		init();
	}
	
	public MainParser(CloseableHttpClient httpClient, CollectParam cpm) throws Exception {
		super();
		this.httpClient = httpClient;
		this.cpm = cpm;
		init();
	}
	
	private void init() throws Exception {
		/*try {
			novelService = NovelObjectPool.getPool().borrowObject(GlobalConfig.localSite.getProgram().getName());
			chapterService = ChapterObjectPool.getPool().borrowObject(GlobalConfig.localSite.getProgram().getName());
			if (GlobalConfig.collect.getBoolean(ConfigKey.CREATE_HTML, false)) {
				//需要生成静态html时， 获取HtmlBuilder对象
				htmlBuilder = HtmlBuilderObjectPool.getPool().borrowObject(GlobalConfig.localSite.getProgram().getName());
			}
		} catch (Exception e) {
			throw new Exception("初始化解析处理器失败,对象池异常！", e);
		}*/
		novelService = new ServiceFactory().createNovelService(GlobalConfig.localSite.getProgram().getName());
		chapterService = new ServiceFactory().createChapterService(GlobalConfig.localSite.getProgram().getName());
		if (GlobalConfig.collect.getBoolean(ConfigKey.CREATE_HTML, false)) {
			//需要生成静态html时， 获取HtmlBuilder对象
			htmlBuilder = new ServiceFactory().createHtmlBuilder(GlobalConfig.localSite.getProgram().getName());
		}
	}

	//TODO 获取最新更新页面的最后更新章节， 如果数据库中存在则说明已采集， 否则更新
	public void process() {
		for (String novelNo : cpm.getNumList()) {
			try {
				
				String infoURL = ParseHelper.getInfoRUL(cpm, novelNo);
				 
				//小说信息页源码
				String infoSource = ParseHelper.getSource(httpClient, cpm, infoURL);
				
				// 获取书名
	            String novelName = ParseHelper.getNovelName(infoSource, cpm);
	            if(novelName==null || novelName.isEmpty()){
	            	throw new BaseException("小说名为空, 目标链接："+infoURL);
	            }
	            
	            // 判断小说是否已经存在， 然后根据配置中的是否添加新书，决定是否继续采集
	            NovelEntity novel = novelService.find(novelName);
	            
	        	if(novel != null) {
	        		//如果书籍已存在则从数据库中取出， 如果是修复模式则更新书籍信息
	        		if(cpm.getCollectType()==ParamEnum.REPAIR_ALL || cpm.getCollectType()==ParamEnum.REPAIR_ASSIGN) {
	        			NovelEntity newNovel = getNovelInfo(infoSource, novelName);
	        			novelService.repair(novel, newNovel);
	        			//修复参数中包含封面时重新下载封面
	        			if(cpm.getRepairParam() != null 
	        					&& cpm.getRepairParam().contains(RepairParamEnum.COVER.getValue())) {
	        				getCover(infoSource, novel);
	        			}
	        		}
	        	} else {
	        		//如果书籍不存在则判断是否允许新书入库， 如果允许则抓取书籍信息
	        		if(cpm.getCollectType()==ParamEnum.COLLECT_All || cpm.getCollectType()==ParamEnum.COLLECT_ASSIGN){
	        			if(GlobalConfig.collect.getBoolean(ConfigKey.ADD_NEW_BOOK, false)) {
		        			novel = addNovel(infoSource, novelName);
		        		}
	        		}
	        	}
	        	//-i参数， 只入库小说， 不采集章节
	        	if(cpm.getCollectType()==ParamEnum.IMPORT) {
	        		if(novel == null)
	        			novel = addNovel(infoSource, novelName);
	        	} else if(novel!=null) {
	            	parse(novelNo, novel, infoSource);
	            }
			} catch(Exception e) {
				logger.error("解析异常, 原因："+e.getMessage(), e);
                continue;
			}
		}
		//归还， 下次循环使用相同的对象， 其实novelService和chapterService不需要用对象池
//		if(novelService != null)
//				NovelObjectPool.getPool().returnObject(GlobalConfig.localSite.getProgram().getName(), novelService);
//		if(chapterService != null)
//				ChapterObjectPool.getPool().returnObject(GlobalConfig.localSite.getProgram().getName(), chapterService);
	}

	/**
	 * 小说入库
	 * @param infoSource
	 * @param novelName
	 * @return
	 * @throws Exception 
	 */
	private NovelEntity addNovel(String infoSource, String novelName)
			throws Exception {
		NovelEntity novel = getNovelInfo(infoSource, novelName);
		
		String pinyin = novelName;
		//处理多音字情况
		for(DuoYinZi dyz : GlobalConfig.duoyin) {
			pinyin = novelName.replace(dyz.getName(), dyz.getPinyin());
		}
		pinyin = PinYinUtils.getFullSpell(pinyin).trim();
		Integer count = novelService.getMaxPinyin(pinyin).intValue();
		if(count > 0){
			pinyin = pinyin + (count+1);
		}
		novel.setPinyin(pinyin);
		novel.setInitial(PinYinUtils.getPinyinShouZiMu(pinyin));
		novel.setNovelNo(novelService.saveNovel(novel));
		//下载小说封面
		getCover(infoSource, novel);
		return novel;
	}
	
	/**
	 * 
	 * <p>解析核心方法， 解析小说信息页</p>
	 * @param novelNo		目标站小说号
	 * @param novel			本地小说对象
	 * @param infoSource	信息页源码
	 * @throws Exception
	 */
	private void parse(String novelNo, NovelEntity novel, String infoSource) throws Exception {
		// 小说目录页地址
        String novelPubKeyURL = ParseHelper.getNovelMenuURL(infoSource, novelNo, cpm);
        
        // 小说目录页内容
        String menuSource = ParseHelper.getChapterListSource(novelPubKeyURL, cpm);
        
        // 根据内容取得章节名
        List<String> chapterNameList = ParseHelper.getChapterNameList(menuSource, cpm);
        // 获得章节地址(章节编号)，所获得的数量必须和章节名相同
        List<String> chapterKeyList = ParseHelper.getChapterNoList(menuSource, cpm);

        if (chapterNameList.size() != chapterKeyList.size()) {
            logger.warn("小说【" + novel.getNovelName() + "】章节名称数和章节地址数不一致， 可能导致采集结果混乱！");
        }
        
        ChapterEntity chapter = new ChapterEntity();
        chapter.setNovelNo(novel.getNovelNo());
        chapter.setNovelName(novel.getNovelName());
        
        //修复
        if(cpm.getCollectType() == ParamEnum.REPAIR_ALL || cpm.getCollectType() == ParamEnum.REPAIR_ASSIGN) {
        	repaireChapter(novelNo, novel, novelPubKeyURL, chapterNameList,
					chapterKeyList);
        } else {
        	//为防止其他小说中存在同名章节情况， 使用以下方式进行采集判断
	        normalCollect( novelNo, novel, chapter, novelPubKeyURL,
					chapterNameList, chapterKeyList);
        }
        
	}
	
	/**
     * <p>正常采集</p>
     * @param novelNo 			目标站小说号
     * @param novel				为本地站构造的小说对象
     * @param chapter			为本地站构造的章节对象
     * @param novelPubKeyURL	目标站小说内容页采集地址
     * @param chapterNameList	从目标站获取的章节名称列表
     * @param chapterKeyList	从目标站获取的章节序号列表
     * @throws Exception
     */
	private void normalCollect(String novelNo, NovelEntity novel, ChapterEntity chapter,
			String novelPubKeyURL, List<String> chapterNameList, List<String> chapterKeyList) throws Exception {
		//获取已经存在的章节列表
		List<ChapterEntity> chapterListDB = chapterService.getChapterList(novel);
    	for(int i=0;i<chapterNameList.size();i++){
    		String cname = chapterNameList.get(i).trim();
    		boolean needCollect = true;
    		for(ChapterEntity tc:chapterListDB){
    			//章节存在则不做处理， 否则采集
    			if(cname.equalsIgnoreCase(tc.getChapterName().trim())){
    				needCollect = false;
    				break;
    			}
    		}
    		if(needCollect){
    			String cno = chapterKeyList.get(i);
				chapter.setChapterName(cname);
				logger.info("采集小说: {}，章节：{}", new Object[] { novel.getNovelName(), cname});
			    collectChapter( novelNo, cno, novelPubKeyURL, novel, chapter);
    		}
    	}
	}

	/**
	 * 
	 * <p>修复错误章节</p>
	 * @param novelNo			目标站小说号
	 * @param novel				本地站小说对象
	 * @param novelPubKeyURL	目录页地址
	 * @param chapterNameList	采集到的章节名列表
	 * @param chapterKeyList	采集到的章节序号列表
	 * @throws Exception
	 */
	private void repaireChapter(String novelNo, NovelEntity novel,
			String novelPubKeyURL, List<String> chapterNameList,
			List<String> chapterKeyList) throws Exception {
		ChapterEntity chapter = null;
		List<ChapterEntity> chapterListDB = chapterService.getChapterList(novel);
		//修复空章节
		for(int i=0;i<chapterNameList.size();i++){
			String cname = chapterNameList.get(i);
			for(ChapterEntity tc:chapterListDB){
				//章节已存在的时候判断该章节对应的txt文件是否存在， 如果不存在则采集，存在不做处理
				if(cname.equalsIgnoreCase(tc.getChapterName())){
					chapter = chapterService.getChapterByChapterNameAndNovelNo(tc);
					if(chapter != null){
						String txtFile = FileHelper.getTxtFilePath(chapter);
						if(!new File(txtFile).exists()){
							logger.info("修复小说: {}，章节：{}", new Object[] { novel.getNovelName(), cname});
		 					collectChapter(novelNo, chapterKeyList.get(i), novelPubKeyURL, novel, chapter);
						}
					}
					break;
				}
			}
		}
	}
	
	/**
	 * 
	 * <p>采集入库主方法</p>
	 * @param novelNo			目标站小说号	
	 * @param cno				目标站章节号
	 * @param novelPubKeyURL	
	 * @param novel
	 * @param tc
	 * @throws Exception
	 */
	private void collectChapter(String novelNo,String cno, String novelPubKeyURL, 
			NovelEntity novel, ChapterEntity tc) throws Exception {
		
		ChapterEntity chapter = tc.clone();
		
		// 章节地址-不完全地址
		String chapterURL = ParseHelper.getChapterURL(novelPubKeyURL, novelNo, cno, cpm);

		// 章节页源码
		String chapterSource = ParseHelper.getChapterSource(chapterURL, cpm);
		// 章节内容
		String chapterContent = ParseHelper.getChapterContent(chapterSource, cpm);

		int chapterOrder = chapterService.getChapterOrder(chapter);
		chapter.setChapterOrder(chapterOrder);
		chapter.setSize(chapterContent.length());
		
		Integer chapterNo = 0;
		if(chapter.getChapterNo()==null||chapter.getChapterNo()==0){
			chapterNo = chapterService.save(chapter).intValue();
			// 只有新采集的章节才会在保存后更新小说信息
			Map<String, Object> totalMap = chapterService.getTotalInfo(novel.getNovelNo());
			novel.setChapters(ObjectUtils.obj2Int(totalMap.get("count")));
			novel.setLastChapterName(chapter.getChapterName());
			novel.setLastChapterno(chapterNo);
			novel.setSize(ObjectUtils.obj2Int(totalMap.get("size")));
			novelService.update(novel);
			
			chapter.setChapterNo(chapterNo);
		} else {
			chapterNo = chapter.getChapterNo();
		}
		
		if (StringUtils.isBlank(chapterContent)) {
		    logger.error("章节内容采集出错， 目标地址：{}， 本站小说号：{}， 章节号：{}", 
		    		new Object[] { chapterURL, novel.getNovelNo() ,chapterNo });
		}
		FileHelper.writeTxtFile(novel, chapter, chapterContent);
		if (GlobalConfig.collect.getBoolean(ConfigKey.CREATE_HTML, false)) {
			ChapterEntity nextChapter = chapterService.get(chapter, 1);
			ChapterEntity preChapter = chapterService.get(chapter, -1);
			PreNextChapter preNext = null;
			//上一章存在说明当前章节不是本书第一章， 生成静态页的时候需要重新生成上一章
			if(preChapter != null){
				//重新产生上个章节的html内容
				//获取上页的上页
				ChapterEntity pre2Chapter = chapterService.get(chapter, -2);
		        preNext = getPreNext(pre2Chapter, chapter, novel);
		        String preChapterContent = htmlBuilder.loadChapterContent(preChapter);
		        htmlBuilder.buildChapterCntHtml(novel, preChapter, preChapterContent, preNext);
			}
		    //生成当前章节的html内容
		    preNext = getPreNext(preChapter, nextChapter, novel);
		    htmlBuilder.buildChapterCntHtml(novel, chapter, chapterContent, preNext);
		    htmlBuilder.buildChapterListHtml(novel, chapterService.getChapterList(novel));
		}
	}

	/**
	 * 获取当前章节的上个章节、下个章节
	 * @param pre
	 * @param next
	 * @param novelNo
	 * @return
	 * @throws Exception 
	 */
	private PreNextChapter getPreNext(ChapterEntity pre, ChapterEntity next, NovelEntity novel) throws Exception {
		PreNextChapter pn = new PreNextChapter();
		//获取目录页地址
        String novelPubKeyURL = GlobalConfig.localSite.getTemplate().getChapterURL();
        int novelNo = novel.getNovelNo().intValue();
        
        novelPubKeyURL = novelPubKeyURL.replace("#subDir#", String.valueOf(novelNo/1000))
        		.replace("#articleNo#", String.valueOf(novelNo));
        
        if(GlobalConfig.localSite.getUsePinyin() == 1) {
        	novelPubKeyURL = novelPubKeyURL.replace("#pinyin#", novel.getPinyin());
		}
        
        novelPubKeyURL = StringUtils.getFullUrl(GlobalConfig.localSite.getSiteUrl(), novelPubKeyURL);
        
        // 如果上一章不存在，则url赋值为目录页地址
        if (pre == null) {
            pn.setPreURL(novelPubKeyURL);
        } else {
        	pn.setPreURL(getLocalChapterUrl(GlobalConfig.localSite.getTemplate().getReaderURL(),
        			novel, pre.getChapterNo()));
        }
        // 下一章
        if (next == null) {
            pn.setNextURL(novelPubKeyURL);
        } else {
            pn.setNextURL(getLocalChapterUrl(GlobalConfig.localSite.getTemplate().getReaderURL(),
            		novel, next.getChapterNo()));
        }
        // 目录页地址
        pn.setChapterListURL(novelPubKeyURL);
		return pn;
	}
	
	private String getLocalChapterUrl(String url, NovelEntity novel, Integer chapterNo) throws Exception {
		int novelNo = novel.getNovelNo().intValue();
        url = url.replace("#subDir#", String.valueOf(novelNo/1000))
				.replace("#articleNo#", String.valueOf(novelNo))
				.replace("#chapterNo#", String.valueOf(chapterNo));
        if(GlobalConfig.localSite.getUsePinyin() == 1) {
        	url = url.replace("#pinyin#", novel.getPinyin());
		}
        // 章节地址-全路径
        url = StringUtils.getFullUrl(GlobalConfig.localSite.getSiteUrl(), url);
        return url;
    }

	/**
	 * 
	 * <p>获取小说信息</p>
	 * @param infoSource
	 * @param novelName
	 * @return
	 */
	private NovelEntity getNovelInfo(String infoSource, String novelName) {

		NovelEntity novel = new NovelEntity();
		novel.setNovelName(novelName);

//		String initial = PinYinUtils.getFirst1Spell(novelName);
//		novel.setInitial(initial);
		
		String author = ParseHelper.getNovelAuthor(infoSource, cpm);
		novel.setAuthor(author);
		
		String topCat = "";
		Integer cat = 0;
		//正常采集  或者  修复参数中包含对应项时才会采集对应项
		if(willParse(RepairParamEnum.TOP.getValue())) {
			topCat = ParseHelper.get(infoSource, cpm.getRuleMap().get(Rule.RegexNamePattern.LAGER_SORT));
	        cat = ParseHelper.getCategory(topCat, CategoryGradeEnum.TOP);
	        novel.setTopCategory(cat);
		}
        
		if(willParse(RepairParamEnum.SUB.getValue())) {
	        String smallSort = ParseHelper.get(infoSource, cpm.getRuleMap().get(Rule.RegexNamePattern.SMALL_SORT));
	        cat = ParseHelper.getCategory(smallSort, CategoryGradeEnum.SUB);
	        novel.setSubCategory(cat);
		}
        
		if(willParse(RepairParamEnum.INTRO.getValue())) {
			String intro = ParseHelper.getNovelIntro(infoSource, cpm);
	        novel.setIntro(StringUtils.isBlank(intro)?"":intro);
		}
        
		if(willParse(RepairParamEnum.KEYWORDS.getValue())) {
			String keywords = ParseHelper.getNovelKeywrods(infoSource, cpm);
			novel.setKeywords(StringUtils.isBlank(keywords)?"":keywords);
		}
		if(willParse(RepairParamEnum.DEGREE.getValue())) {
	        String novelDegree = ParseHelper.get(infoSource, cpm.getRuleMap().get(Rule.RegexNamePattern.NOVEL_DEGREE));
	        String fullFlagStr = GlobalConfig.collect.getString(ConfigKey.FULL_FLAG, "已完结");
	        // 完本为true， 连载false
	        boolean fullFlag = fullFlagStr.equals(novelDegree) ? true : false;
	        novel.setFullFlag(fullFlag);
		}
        
	    return novel;
	}

	/**
	 * 根据采集参数判断具体的采集项目是否解析
	 * @param param
	 * @return
	 */
	private boolean willParse(String param) {
		return cpm.getCollectType()==ParamEnum.COLLECT_All 
			|| cpm.getCollectType()==ParamEnum.COLLECT_ASSIGN
			|| cpm.getCollectType()==ParamEnum.IMPORT
			|| ((cpm.getCollectType()==ParamEnum.REPAIR_ALL 
					|| cpm.getCollectType()==ParamEnum.REPAIR_ASSIGN)
				&& cpm.getRepairParam() != null && cpm.getRepairParam().contains(param));
	}

	/**
	 * 
	 * <p>获取小说封面图片类型， 并下载封面</p>
	 * @param infoSource
	 * @param novel
	 * @throws Exception 
	 */
	private void getCover(String infoSource, NovelEntity novel) throws Exception {
		Integer imgFlag = ParseHelper.getNovelCover(novel, infoSource, cpm);
        novel.setImgFlag(imgFlag);
	}

	public CloseableHttpClient getHttpClient() {
		return httpClient;
	}

	public void setHttpClient(CloseableHttpClient httpClient) {
		this.httpClient = httpClient;
	}

	public CollectParam getCpm() {
		return cpm;
	}

	public void setCpm(CollectParam cpm) {
		this.cpm = cpm;
	}

}