package luxe.texture3d.app

import com.google.android.filament.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SelectionBoundsRenderer(private val engine:Engine,private val scene:Scene,materialBuffer:ByteBuffer){
    private val material=Material.Builder().payload(materialBuffer,materialBuffer.remaining()).build(engine)
    private val instance=material.createInstance().apply{setParameter("lineColor",0.22f,0.72f,1f,1f)}
    private var entity=0;private var vertices:VertexBuffer?=null;private var indices:IndexBuffer?=null
    fun show(center:FloatArray,half:FloatArray){clear();val x=half[0]*1.04f;val y=half[1]*1.04f;val z=half[2]*1.04f;val cx=center[0];val cy=center[1];val cz=center[2];val corners=arrayOf(floatArrayOf(cx-x,cy-y,cz-z),floatArrayOf(cx+x,cy-y,cz-z),floatArrayOf(cx+x,cy+y,cz-z),floatArrayOf(cx-x,cy+y,cz-z),floatArrayOf(cx-x,cy-y,cz+z),floatArrayOf(cx+x,cy-y,cz+z),floatArrayOf(cx+x,cy+y,cz+z),floatArrayOf(cx-x,cy+y,cz+z));val edges=intArrayOf(0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7);val points=FloatArray(edges.size*3);edges.forEachIndexed{i,index->points[i*3]=corners[index][0];points[i*3+1]=corners[index][1];points[i*3+2]=corners[index][2]};val data=ByteBuffer.allocateDirect(points.size*4).order(ByteOrder.nativeOrder());data.asFloatBuffer().put(points);vertices=VertexBuffer.Builder().vertexCount(edges.size).bufferCount(1).attribute(VertexBuffer.VertexAttribute.POSITION,0,VertexBuffer.AttributeType.FLOAT3,0,12).build(engine).also{it.setBufferAt(engine,0,data)};val indexData=ByteBuffer.allocateDirect(edges.size*2).order(ByteOrder.nativeOrder());for(i in edges.indices)indexData.putShort(i.toShort());indexData.flip();indices=IndexBuffer.Builder().indexCount(edges.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine).also{it.setBuffer(engine,indexData)};entity=EntityManager.get().create();RenderableManager.Builder(1).boundingBox(Box(cx,cy,cz,x,y,z)).geometry(0,RenderableManager.PrimitiveType.LINES,vertices!!,indices!!).material(0,instance).culling(false).castShadows(false).receiveShadows(false).build(engine,entity);scene.addEntity(entity)}
    fun clear(){if(entity!=0){scene.removeEntity(entity);engine.destroyEntity(entity);EntityManager.get().destroy(entity);entity=0};vertices?.let{engine.destroyVertexBuffer(it)};indices?.let{engine.destroyIndexBuffer(it)};vertices=null;indices=null}
    fun destroy(){clear();engine.destroyMaterialInstance(instance);engine.destroyMaterial(material)}
}
