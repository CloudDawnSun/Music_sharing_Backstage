package com.zhuanye.music_system.controller;


import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mpatric.mp3agic.*;
import com.zhuanye.music_system.entity.Song;
import com.zhuanye.music_system.service.SongService;
import com.zhuanye.music_system.support.PageRequest;
import com.zhuanye.music_system.utils.FileUtil;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


import javax.annotation.Resource;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/song")
public class SongController {

    @Resource
    SongService songService;

    @Value("${song_path}")
    String song_path;


    @RequestMapping(value = "/findSongById/{id}", method = RequestMethod.GET)
    public Song getSongById(@PathVariable int id) {
        return songService.selectById(id);
    }

    @RequestMapping("/findSongByNameOrAuthor")
    public PageInfo<Song> findSongByName(String name, PageRequest pageRequest){
        return songService.getPageInfoByNameOrAuthor(pageRequest, name);
    }

    @RequestMapping("/songList")
    public PageInfo<Song> SongList(PageRequest pageRequest){
        return songService.getPageInfoTotal(pageRequest);
    }

    @RequestMapping("/uploadSong")
    public boolean uploadSong(Song song, MultipartFile file) {
        if (file == null) {
            return false;
        }
        song.setUploaderTime(new Date());
        String fileNewName =  UUID.randomUUID().toString()+".mp3";
        File f = new File(song_path+file.getOriginalFilename());
        try {
            if (f.exists()){
                f.delete();
            }
            f.createNewFile();
            file.transferTo(f.getAbsoluteFile());
            Mp3File mp3File = new Mp3File(f);
            ID3v2 id3v2Tag = null;
            if (mp3File.hasId3v2Tag()) {
                id3v2Tag = mp3File.getId3v2Tag();
            }else {
                id3v2Tag = new ID3v23Tag();
            }
            id3v2Tag.setTitle(song.getName());
            id3v2Tag.setArtist(song.getAuthor());
            // 歌词
            if (song.getLyric() != null) {
                songService.transformLyric(song);
                id3v2Tag.setLyrics(song.getLyric());
            }
            mp3File.setId3v2Tag(id3v2Tag);
            mp3File.save(song_path+fileNewName);
            song.setPath(fileNewName);
            songService.insertSong(song);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }finally {
            f.delete();
        }
        return true;
    }

    @RequestMapping("/downloaderOneSong/{path}")
    public void downloaderOneSong(@PathVariable String path, HttpServletResponse response){
        InputStream in = FileUtil.getInputStream(song_path+path);
        if (in == null) {
            response.setStatus(404);
        } else {
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-type","audio/mpeg");
            /**
             *  支持断点下载
             */
            response.addHeader("Accept-Ranges","bytes");
            try (OutputStream outputStream = response.getOutputStream()){
                response.setContentLengthLong(in.available());
                /*//缓存一星期
                response.setHeader("Cache-Control","max-age="+3600*24*7);*/
                response.addHeader("Content-disposition", "attchement; filename="+URLEncoder.encode(new Mp3File(song_path+path).getId3v2Tag().getTitle()+".mp3","UTF-8"));
                IOUtils.copy(in, outputStream);
                outputStream.flush();
                response.flushBuffer();
                outputStream.close();
                // 增加下载次数
                songService.increaseDownloadNumber(path);
            } catch (Exception e) {
                System.err.println("拷贝出错");
                response.setStatus(502);
            } finally {
                if (in != null) {
                    try {
                        in.close();

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }


    @RequestMapping(value = "/downloaderManySongs")
    public void downloaderManySongs(String[] paths, HttpServletResponse response) {
        if (paths == null) {
            response.setStatus(400);
            return;
        }
        File zipFile = null;
        BufferedInputStream in = null;
        try{
//            JSONArray objects = JSONArray.parseArray(paths);
//            zipFile = FileUtil.compress(objects.toJavaList(String.class), song_path, "music_system_songs.zip");
            zipFile = FileUtil.compress(paths, song_path, "music_system_songs.zip");
            if (zipFile != null) {
                in = new BufferedInputStream(new FileInputStream(zipFile));
                OutputStream outputStream = response.getOutputStream();
                response.setCharacterEncoding("utf-8");
                response.setHeader("Content-type","application/zip");
                response.setContentLengthLong(in.available());
                response.addHeader("Content-disposition", "attchement; filename="+zipFile.getName());
                IOUtils.copy(in, outputStream);
                outputStream.flush();
                response.flushBuffer();
                outputStream.close();
            }
        } catch (JSONException e) {
            //参数格式错误
            response.setStatus(400);
            return;
        } catch (IOException e) {
            response.setStatus(502);
            e.printStackTrace();
        }finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            zipFile.delete();
        }
    }


//    @RequestMapping("/downloader")
//    public ResponseEntity<byte[]> downloade(String path, int id) {
//        songService.increaseDownloadNumber(id);
//        return getSongFileByPath(path);
//    }
//    @RequestMapping("/getSongFile/{path}")
//    public ResponseEntity<byte[]> getSongFileByPath(@PathVariable String path){
//        String path2 = song_path+"d55d87cc-31eb-42c8-98ab-208c31e89ff8.mp3";
//        byte[] body = null;
//        InputStream in = FileUtil.getInputStream(path2);
//        if (in != null) {
//            try {
//                HttpHeaders headers = new HttpHeaders();
//                body = new byte[in.available()];
//                headers.setContentLength(in.available());
//                in.read(body);
//                headers.add("Accept-Ranges","bytes");
//                headers.add("Expires", String.valueOf(System.currentTimeMillis() + 1000*3600));
//                headers.add("Content-Disposition", "attchement;filename="+URLEncoder.encode(new Mp3File(path2).getId3v2Tag().getTitle()+".mp3","UTF-8"));
//                headers.add("Content-type","audio/mpeg");
//                headers.add("charset","utf-8");
//                HttpStatus statusCode = HttpStatus.OK;
//                ResponseEntity<byte[]> entity = new ResponseEntity<byte[]>(body, headers, statusCode);
//                return entity;
//            } catch (Exception e) {
//                System.err.println("拷贝出错");
//            } finally {
//                if (in != null) {
//                    try {
//                        in.close();
//
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//
//        }
//        return null;
//    }

}
